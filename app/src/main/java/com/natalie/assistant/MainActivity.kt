package com.natalie.assistant

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.sqrt

/**
 * M1: Simple test UI (no AndroidX — uses framework APIs, minSdk 31).
 * - Start Listening: requests RECORD_AUDIO (+ POST_NOTIFICATIONS on 13+), starts service.
 * - Stop Listening: stops service, releases mic.
 * - State + event log refresh on a timer while visible.
 */
class MainActivity : Activity() {

    private lateinit var stateText: TextView
    private lateinit var logText: TextView
    private lateinit var diagText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var replyText: TextView
    private lateinit var speakingText: TextView
    /** M2.5: thinking indicator during WAITING_REPLY. */
    private lateinit var thinkingText: TextView
    /** M2.5: follow-up indicator during CONVERSATION_PAUSE. */
    private lateinit var conversationText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var micTestButton: Button
    private lateinit var copyTranscriptButton: Button
    private lateinit var copyReplyButton: Button
    private lateinit var copyLogButton: Button
    /** M2.4: hands-free toggle — persistent always-on listening. */
    private lateinit var handsFreeSwitch: Switch
    private var handsFreeListenerActive = true

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRefresh = object : Runnable {
        override fun run() {
            refreshUi()
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateText = findViewById(R.id.stateText)
        logText = findViewById(R.id.logText)
        diagText = findViewById(R.id.diagText)
        transcriptText = findViewById(R.id.transcriptText)
        replyText = findViewById(R.id.replyText)
        speakingText = findViewById(R.id.speakingText)
        thinkingText = findViewById(R.id.thinkingText)
        conversationText = findViewById(R.id.conversationText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        micTestButton = findViewById(R.id.micTestButton)
        copyTranscriptButton = findViewById(R.id.copyTranscriptButton)
        copyReplyButton = findViewById(R.id.copyReplyButton)
        copyLogButton = findViewById(R.id.copyLogButton)
        handsFreeSwitch = findViewById(R.id.handsFreeSwitch)

        startButton.setOnClickListener { onStartClicked() }
        stopButton.setOnClickListener { onStopClicked() }
        micTestButton.setOnClickListener { onMicTestClicked() }
        copyTranscriptButton.setOnClickListener { onCopyTranscriptClicked() }
        copyReplyButton.setOnClickListener { onCopyReplyClicked() }
        copyLogButton.setOnClickListener { onCopyLogClicked() }
        // M2.4: hands-free toggle. Guard flag prevents programmatic
        // setChecked() (e.g. from onResume sync) from re-firing the listener.
        handsFreeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (handsFreeListenerActive) onHandsFreeToggled(isChecked)
        }
        // Long-press a panel = copy its full text (one-tap alternative to
        // the Copy buttons; panels are also text-selectable for partial copy).
        transcriptText.setOnLongClickListener { onCopyTranscriptClicked(); true }
        replyText.setOnLongClickListener { onCopyReplyClicked(); true }

        ListeningForegroundService.logEvent("MainActivity created (M2.5 conversation)")
        ListeningForegroundService.logEvent(
            "Engine: openWakeWord (ONNX Runtime, on-device, no key needed)"
        )
        // M2.1: surface STT availability loudly at startup — never silent.
        val sttOk = try {
            android.speech.SpeechRecognizer.isRecognitionAvailable(this)
        } catch (e: Exception) {
            false
        }
        ListeningForegroundService.logEvent(
            if (sttOk) "STT check (UI): SpeechRecognizer available"
            else "STT check (UI): SpeechRecognizer NOT available — " +
                "transcription will be skipped"
        )
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(uiRefresh)
        // M2.4: sync the hands-free toggle with the persisted preference.
        // If the pref says ON but the service isn't running (system killed
        // it), say so loudly instead of pretending hands-free is active.
        val prefOn = ListeningForegroundService.readHandsFreePref(this)
        handsFreeListenerActive = false
        handsFreeSwitch.isChecked = prefOn
        handsFreeListenerActive = true
        if (prefOn && !ListeningForegroundService.isServiceRunning()) {
            ListeningForegroundService.logEvent(
                "HANDS-FREE WARNING — toggle is ON but the service is not " +
                    "running (system may have killed it). Toggle OFF then ON " +
                    "to restart hands-free listening."
            )
        }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiRefresh)
    }

    private fun onStartClicked() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 42)
            return
        }
        ListeningForegroundService.logEvent("User tapped Start Listening")
        ListeningForegroundService.startService(this)
    }

    private fun onStopClicked() {
        ListeningForegroundService.logEvent("User tapped Stop Listening")
        ListeningForegroundService.stopService(this)
    }

    /**
     * M2.4: hands-free toggle handler.
     * ON  → needs RECORD_AUDIO (same gate as Start); the service is
     *       started if needed and the WakeLock is acquired.
     * OFF → WakeLock released; the service keeps running in manual mode
     *       until the user taps Stop.
     */
    private fun onHandsFreeToggled(enabled: Boolean) {
        if (enabled) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                // Revert the toggle; the permission flow will restart it.
                handsFreeListenerActive = false
                handsFreeSwitch.isChecked = false
                handsFreeListenerActive = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 44)
                return
            }
            ListeningForegroundService.logEvent(
                "User enabled HANDS-FREE mode — persistent listening")
            Toast.makeText(this,
                "Hands-free ON — say 'Hi Natalie' anytime",
                Toast.LENGTH_LONG).show()
        } else {
            ListeningForegroundService.logEvent("User disabled hands-free mode")
        }
        ListeningForegroundService.setHandsFree(this, enabled)
    }

    // ------------------------------------------------------------------
    // Copy/share helpers — Kareem tests on his phone and reports back,
    // so transcripts, replies, and the event log must be copyable without
    // screenshots. Uses the built-in ClipboardManager, no new dependencies.
    // ------------------------------------------------------------------

    private fun copyToClipboard(label: String, text: String) {
        if (text.isBlank() || text == "—") {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        ListeningForegroundService.logEvent("Copied $label to clipboard (${text.length} chars)")
    }

    private fun onCopyTranscriptClicked() {
        val tx = ListeningForegroundService.latestTranscript
        if (tx.isNotEmpty()) copyToClipboard("transcript", tx)
        else {
            val err = ListeningForegroundService.latestTranscriptError
            if (err.isNotEmpty()) copyToClipboard("transcript error", err)
            else Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onCopyReplyClicked() {
        val rp = ListeningForegroundService.latestReply
        if (rp.isNotEmpty()) copyToClipboard("Natalie's reply", rp)
        else {
            val err = ListeningForegroundService.latestReplyError
            if (err.isNotEmpty()) copyToClipboard("reply error", err)
            else Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onCopyLogClicked() {
        val log = synchronized(ListeningForegroundService.eventLog) {
            ListeningForegroundService.eventLog.joinToString("\n")
        }
        copyToClipboard("event log", log)
    }

    /**
     * M1 diagnostic: records 5 seconds from the microphone and logs the
     * peak + RMS amplitude. Tells us whether ANY audio is reaching the app.
     * Peak near 0 = silence (mic not delivering). Peak in the thousands =
     * mic works (problem is the wake-word model/threshold).
     */
    private fun onMicTestClicked() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 43)
            return
        }
        runMicTest()
    }

    private fun runMicTest() {
        ListeningForegroundService.logEvent("Mic test: recording 5 seconds — SPEAK NOW")
        Toast.makeText(this, "Speak now — recording 5 seconds", Toast.LENGTH_SHORT).show()
        Thread({
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                logOnUi("Mic test FAILED: getMinBufferSize returned $minBuf")
                return@Thread
            }
            var rec: AudioRecord? = null
            try {
                rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    logOnUi("Mic test FAILED: AudioRecord not initialized (state=${rec.state})")
                    return@Thread
                }
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    logOnUi("Mic test FAILED: not recording (state=${rec.recordingState})")
                    return@Thread
                }
                val buf = ShortArray(1600) // 100ms chunks
                var peak = 0
                var sumSq = 0.0
                var total = 0L
                var zeroReads = 0
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) { zeroReads++; continue }
                    for (i in 0 until n) {
                        val s = buf[i].toInt()
                        val a = if (s < 0) -s else s
                        if (a > peak) peak = a
                        sumSq += (s * s).toDouble()
                    }
                    total += n
                }
                val rms = if (total > 0) sqrt(sumSq / total) else 0.0
                val verdict = when {
                    peak < 50 -> "SILENCE — mic delivering nothing (check mic priority / hardware)"
                    peak < 500 -> "VERY QUIET — mic works but barely (speak louder / closer)"
                    else -> "MIC WORKS — audio reaching app (wake-word model is the problem)"
                }
                logOnUi("Mic test DONE: peak=$peak rms=${"%.1f".format(rms)} " +
                    "samples=$total zeroReads=$zeroReads")
                logOnUi("Mic test verdict: $verdict")
            } catch (e: Exception) {
                logOnUi("Mic test FAILED: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                try { rec?.release() } catch (_: Exception) {}
            }

            // Phase 2: try loading the ONNX models directly — surfaces the
            // exact error if the wake-word pipeline can't initialize.
            testOnnxPipeline()
        }, "NatalieMicTest").start()
    }

    private fun testOnnxPipeline() {
        logOnUi("ONNX test: loading wake-word models...")
        try {
            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            logOnUi("ONNX test: OrtEnvironment OK")
            val opts = ai.onnxruntime.OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            val models = listOf(
                "openwakeword/melspectrogram.onnx",
                "openwakeword/embedding_model.onnx",
                "openwakeword/hey_jarvis_v0.1.onnx"
            )
            for (m in models) {
                try {
                    val bytes = assets.open(m).use { it.readBytes() }
                    logOnUi("ONNX test: $m read OK (${bytes.size} bytes)")
                    val session = env.createSession(bytes, opts)
                    val inShapes = session.inputNames.associateWith { name ->
                        try {
                            (session.inputInfo[name]?.info as? ai.onnxruntime.TensorInfo)
                                ?.shape?.toList() ?: "?"
                        } catch (_: Exception) { "?" }
                    }
                    val outShapes = session.outputNames.associateWith { name ->
                        try {
                            (session.outputInfo[name]?.info as? ai.onnxruntime.TensorInfo)
                                ?.shape?.toList() ?: "?"
                        } catch (_: Exception) { "?" }
                    }
                    logOnUi("ONNX test: $m session OK")
                    logOnUi("ONNX test:   inShapes=$inShapes")
                    logOnUi("ONNX test:   outShapes=$outShapes")
                    session.close()
                } catch (e: Exception) {
                    logOnUi("ONNX test FAILED on $m: " +
                        "${e.javaClass.simpleName}: ${e.message}")
                    return
                }
            }
            logOnUi("ONNX test: ALL 3 MODELS LOADED — pipeline can initialize")
            env.close()
        } catch (e: Exception) {
            logOnUi("ONNX test FAILED: ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: UnsatisfiedLinkError) {
            logOnUi("ONNX test FAILED: native lib missing: ${e.message}")
        }
    }

    private fun logOnUi(msg: String) {
        uiHandler.post { ListeningForegroundService.logEvent(msg) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) {
            // Notification permission might be the ONLY pending permission;
            // read the actual mic grant state rather than the returned array.
            val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (micGranted) {
                ListeningForegroundService.logEvent("Mic permission granted — starting service")
                ListeningForegroundService.startService(this)
            } else {
                Toast.makeText(this,
                    "Microphone permission is required for wake-word listening",
                    Toast.LENGTH_LONG).show()
                ListeningForegroundService.logEvent("Mic permission DENIED by user")
            }
        } else if (requestCode == 43) {
            val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (micGranted) {
                runMicTest()
            } else {
                Toast.makeText(this,
                    "Microphone permission is required for the mic test",
                    Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == 44) {
            // M2.4: hands-free toggle requested mic permission.
            val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (micGranted) {
                ListeningForegroundService.logEvent(
                    "Mic permission granted — enabling hands-free mode")
                handsFreeListenerActive = false
                handsFreeSwitch.isChecked = true
                handsFreeListenerActive = true
                ListeningForegroundService.setHandsFree(this, true)
            } else {
                Toast.makeText(this,
                    "Microphone permission is required for hands-free listening",
                    Toast.LENGTH_LONG).show()
                ListeningForegroundService.logEvent("Mic permission DENIED by user")
            }
        }
    }

    private fun refreshUi() {
        val state = ListeningForegroundService.currentState.get()
        val hfTag = if (ListeningForegroundService.handsFreeEnabled) " [hands-free]" else ""
        // M2.5: debug status line shows the WebSocket link state and which
        // transport delivered the latest reply (ws / poll / recovery).
        val wsTag = " [ws:${ListeningForegroundService.wsStatus}]"
        val viaTag = ListeningForegroundService.lastReplyTransport
            .takeIf { it.isNotEmpty() }
            ?.let { " (last reply via $it)" } ?: ""
        stateText.text = "State: $state$hfTag$wsTag" +
            (if (ListeningForegroundService.wakeCount > 0)
                "  (wakes: ${ListeningForegroundService.wakeCount})" else "") +
            (if (ListeningForegroundService.utteranceCount > 0)
                "  (utterances: ${ListeningForegroundService.utteranceCount})" else "") +
            (if (ListeningForegroundService.replyCount > 0)
                "  (replies: ${ListeningForegroundService.replyCount})" else "") +
            viaTag

        // M2.4: keep the hands-free toggle in sync if the service changed
        // the mode behind the UI's back (e.g. Stop tapped from the
        // notification clears hands-free). Guard flag prevents listener loop.
        val hfNow = ListeningForegroundService.handsFreeEnabled
        if (handsFreeSwitch.isChecked != hfNow) {
            handsFreeListenerActive = false
            handsFreeSwitch.isChecked = hfNow
            handsFreeListenerActive = true
        }

        // Transcript panel: latest result, error, or idle placeholder.
        val err = ListeningForegroundService.latestTranscriptError
        val tx = ListeningForegroundService.latestTranscript
        val txTime = ListeningForegroundService.latestTranscriptTime
        transcriptText.text = when {
            tx.isNotEmpty() -> "\"$tx\"\n($txTime)"
            err.isNotEmpty() -> "⚠ $err"
            else -> "—"
        }

        // Reply panel (M2.2): Natalie's latest text response.
        val rErr = ListeningForegroundService.latestReplyError
        val rp = ListeningForegroundService.latestReply
        val rpTime = ListeningForegroundService.latestReplyTime
        replyText.text = when {
            rp.isNotEmpty() -> "\"$rp\"\n($rpTime)"
            rErr.isNotEmpty() -> "⚠ $rErr"
            else -> "—"
        }

        // Speaking indicator (M2.3): Vanessa's voice status.
        val aStat = ListeningForegroundService.audioStatus
        speakingText.text = when {
            state == VoiceState.SPEAKING || aStat == "playing" -> "🔊 Speaking..."
            aStat == "waiting" -> "⏳ Waiting for voice..."
            aStat.isNotEmpty() -> "($aStat)"
            else -> ""
        }

        // M2.5: thinking indicator — a soft human-feeling beat that fills
        // the dead silence while the reply is on its way. Past 8s it
        // acknowledges the wait without alarming.
        thinkingText.text = when {
            state == VoiceState.WAITING_REPLY -> {
                val elapsed = System.currentTimeMillis() -
                    ListeningForegroundService.waitingReplySinceMs
                val dots = ".".repeat(1 + ((elapsed / 700) % 3).toInt())
                if (elapsed > Config.THINKING_LONG_MS) "💭 Still thinking$dots"
                else "💭 Natalie is thinking$dots"
            }
            else -> ""
        }

        // M2.5: conversation indicator — the mic is warm for a follow-up,
        // no wake word needed. Subtle countdown so he feels the window.
        conversationText.text = when {
            state == VoiceState.CONVERSATION_PAUSE -> {
                val remainMs = ListeningForegroundService.conversationEndsAtMs -
                    System.currentTimeMillis()
                val secs = (remainMs / 1000).coerceAtLeast(0)
                "👂 Listening for follow-up… (${secs}s — no wake word needed)"
            }
            else -> ""
        }

        val active = state == VoiceState.STARTING ||
            state == VoiceState.WAKE_IDLE ||
            state == VoiceState.WAKE_DETECTED ||
            state == VoiceState.CAPTURING ||
            state == VoiceState.TRANSCRIBING ||
            state == VoiceState.SENDING ||
            state == VoiceState.WAITING_REPLY ||
            state == VoiceState.REPLY_RECEIVED ||
            state == VoiceState.SPEAKING ||
            state == VoiceState.CONVERSATION_PAUSE
        startButton.isEnabled = !active
        stopButton.isEnabled = active
        // Never run the standalone mic test while the service owns the mic —
        // one AudioRecord owner at a time, no competing sessions.
        micTestButton.isEnabled = !active

        // STT availability line, merged into the diagnostics header.
        val sttLine = "sttAvailable=${ListeningForegroundService.sttAvailable} " +
            "(${ListeningForegroundService.sttEngineInfo})"

        synchronized(ListeningForegroundService.eventLog) {
            logText.text = ListeningForegroundService.eventLog.joinToString("\n")
        }

        // Live thread diagnostics from the instrumented openWakeWord library.
        try {
            diagText.text = sttLine + "\n" +
                com.openwakeword.OpenWakeWord.Diag.summary()
        } catch (_: Exception) { }
    }
}
