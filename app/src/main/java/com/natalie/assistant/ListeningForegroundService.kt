package com.natalie.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import com.openwakeword.OpenWakeWord
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * M2.1 (openWakeWord + on-device STT edition):
 *
 * WAKE_IDLE → "Hi Natalie" → CAPTURING → TRANSCRIBING → WAKE_IDLE.
 *
 * - openWakeWord (Apache 2.0, ONNX Runtime) processes audio locally on-device.
 *   NO network, NO API key, NO account. (Relay arrives in M2.2.)
 * - Single AudioRecord owner: the OpenWakeWord instance. The service controls
 *   its lifecycle (pause/resume) — the mic is never shared or competed for.
 *   During CAPTURING/TRANSCRIBING the wake pipeline is paused and the mic is
 *   handed to Android's SpeechRecognizer (serialized handoff, never parallel).
 * - SpeechRecognizer performs utterance capture + endpointing on-device
 *   (EXTRA_PREFER_OFFLINE). The M2 plan's VAD parameters map to recognizer
 *   intent extras: 1.5s complete-silence timeout, 2s minimum length.
 *   Rationale: Android's SpeechRecognizer cannot consume pre-recorded PCM,
 *   so it must own the mic during the utterance. Our instrumentation tracks
 *   the session via its callbacks (onRmsChanged, onBeginning/EndOfSpeech).
 * - One serialized state controller: all transitions happen on the main
 *   thread via setState() (guarded). The audio thread never transitions.
 * - A state is only shown as active when its operation is confirmed:
 *   CAPTURING requires onReadyForSpeech; WAKE_IDLE requires verified frames.
 */
class ListeningForegroundService : Service() {

    companion object {
        const val TAG = "NatalieM1"
        const val CHANNEL_ID = "natalie_listening"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.natalie.assistant.action.START"
        const val ACTION_STOP = "com.natalie.assistant.action.STOP"
        /** M2.4: toggle hands-free mode. Extra: EXTRA_HANDSFREE_ENABLED (boolean). */
        const val ACTION_SET_HANDSFREE = "com.natalie.assistant.action.SET_HANDSFREE"
        const val EXTRA_HANDSFREE_ENABLED = "handsfree_enabled"

        /** M2.4: hands-free mode state (runtime mirror of the persisted pref). */
        @Volatile var handsFreeEnabled = false

        /** M2.4: is the service process actually running? (for UI sync) */
        @Volatile private var serviceRunning = false
        fun isServiceRunning(): Boolean = serviceRunning

        // Shared state for MainActivity display
        val currentState = AtomicReference(VoiceState.OFF)
        val eventLog = mutableListOf<String>()
        var wakeCount = 0
        var utteranceCount = 0

        /** Latest transcription result for the UI. */
        @Volatile var latestTranscript: String = ""
        @Volatile var lastPartialText: String = ""
        @Volatile var latestTranscriptTime: String = ""
        @Volatile var latestTranscriptError: String = ""

        /** Latest relay reply from Natalie for the UI (M2.2). */
        @Volatile var latestReply: String = ""
        @Volatile var latestReplyTime: String = ""
        @Volatile var latestReplyError: String = ""
        var replyCount = 0

        /** TTS audio status for the UI (M2.3): "", "waiting", "playing", or error text. */
        @Volatile var audioStatus: String = ""

        /** M2.5: which transport delivered the latest reply ("ws", "poll", "recovery", "ws-catchup"). */
        @Volatile var lastReplyTransport: String = ""

        /** M2.5: WebSocket link state for the UI ("up"/"down"). */
        @Volatile var wsStatus: String = "down"

        /** M2.5: when WAITING_REPLY began (for the thinking indicator). */
        @Volatile var waitingReplySinceMs: Long = 0L

        /** M2.5: when the CONVERSATION_PAUSE window ends (for the countdown). */
        @Volatile var conversationEndsAtMs: Long = 0L

        /** Message IDs whose TTS has already been played — never play twice. */
        private val playedMessageIds = mutableSetOf<String>()

        @Synchronized fun markPlayed(messageId: String) { playedMessageIds.add(messageId) }
        @Synchronized fun wasPlayed(messageId: String): Boolean = playedMessageIds.contains(messageId)

        /** STT engine availability, checked at startup. */
        @Volatile var sttAvailable: Boolean? = null
        @Volatile var sttEngineInfo: String = "not checked"

        @Synchronized
        fun logEvent(msg: String) {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "[$ts] $msg"
            Log.i(TAG, line)
            synchronized(eventLog) {
                eventLog.add(line)
                if (eventLog.size > 200) eventLog.removeAt(0)
            }
        }

        fun startService(context: Context) {
            val intent = Intent(context, ListeningForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ListeningForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * M2.4: toggle hands-free mode from the UI.
         * ON  → persist pref, ensure the service is running, then enable
         *       (WakeLock + persistent notification).
         * OFF → persist pref, release WakeLock; the service keeps running in
         *       manual Start/Stop mode until the user taps Stop.
         */
        fun setHandsFree(context: Context, enabled: Boolean) {
            context.getSharedPreferences(Config.UI_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(Config.HANDSFREE_ENABLED_KEY, enabled).apply()
            handsFreeEnabled = enabled
            if (enabled) {
                // Ensure the service is up first (intents are processed in
                // order: START is handled before SET_HANDSFREE).
                startService(context)
            }
            val intent = Intent(context, ListeningForegroundService::class.java).apply {
                action = ACTION_SET_HANDSFREE
                putExtra(EXTRA_HANDSFREE_ENABLED, enabled)
            }
            context.startService(intent)
        }

        /** M2.4: read the persisted hands-free preference. */
        fun readHandsFreePref(context: Context): Boolean =
            context.getSharedPreferences(Config.UI_PREFS, Context.MODE_PRIVATE)
                .getBoolean(Config.HANDSFREE_ENABLED_KEY, false)
    }

    /** Per-utterance instrumentation — one session per wake. */
    private data class UtteranceSession(
        val wakeTimestamp: String,
        val wakeScore: Float,
        var captureStartElapsed: Long = 0L,
        var readyForSpeechElapsed: Long = 0L,
        var speechBeginElapsed: Long = 0L,
        var speechEndElapsed: Long = 0L,
        var rmsMin: Float = Float.MAX_VALUE,
        var rmsMax: Float = Float.MIN_VALUE,
        var rmsSamples: Int = 0,
        var resultElapsed: Long = 0L,
        var transcript: String? = null,
        var error: String? = null
    ) {
        fun summary(): String {
            val totalMs = if (resultElapsed > 0) resultElapsed - captureStartElapsed else -1
            val rmsRange = if (rmsSamples > 0)
                "rms=[${"%.1f".format(rmsMin)}..${"%.1f".format(rmsMax)}]dB n=$rmsSamples"
            else "rms=no samples"
            return "wakeScore=${"%.3f".format(wakeScore)} totalMs=$totalMs $rmsRange " +
                "transcript=${transcript ?: "<none>"} error=${error ?: "<none>"}"
        }
    }

    @Volatile private var running = false
    private var detector: OpenWakeWord? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var utterance: UtteranceSession? = null
    /** Active TTS playback (M2.3) — released on completion, error, or cleanup. */
    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var failsafeRunnable: Runnable? = null

    // ------------------------------------------------------------------
    // M2.5: WebSocket push + conversation mode.
    // ------------------------------------------------------------------

    /** WebSocket push transport (null until startListening connects it). */
    private var relayWs: RelayWebSocket? = null

    /**
     * Message IDs whose reply has already been displayed, whichever
     * transport delivered it. claimReply() is the single dedup gate:
     * the HTTP poll loop, the WebSocket, and startup recovery all race
     * here and exactly one winner proceeds to handleIncomingReply().
     */
    private val handledReplyIds = mutableSetOf<String>()

    /** Wakes the audio poll thread immediately on a WS audio-ready event. */
    private val audioSignal = Object()

    /** Message ID the audio poll thread is currently waiting on (if any). */
    @Volatile private var audioWaitMessageId: String? = null

    /** Fires when the 12s conversation window expires with no follow-up. */
    private var conversationTimeoutRunnable: Runnable? = null

    /** Consecutive recognizer restarts inside one conversation window. */
    private var conversationRestarts = 0

    // ------------------------------------------------------------------
    // V1: RealtimeConversationController — turn ownership via generation.
    //
    // Every user turn gets a monotonically increasing generation. Only a
    // genuinely new turn advances it: wake from WAKE_IDLE, follow-up
    // captured in the conversation window, explicit Stop, session reset.
    // Duplicate wake_requested events, WS reconnects, recognition retries,
    // and polling retries NEVER advance it.
    //
    // Every async callback captures (generation, messageId) when its work
    // starts and validates before acting: a stale callback may not start
    // playback, stop a newer player, overwrite state, or reopen listening.
    // ------------------------------------------------------------------

    /** One user turn, from wake/follow-up through reply playback. */
    private data class Turn(
        val turnId: String,
        val generation: Long,
        var messageId: String?,
        val startedAtElapsed: Long,
        var hintSentAtElapsed: Long = 0L,
        var messageSentAtElapsed: Long = 0L,
        var claimedAtElapsed: Long = 0L,
        var repliedAtElapsed: Long = 0L,
        var audioReadyAtElapsed: Long = 0L,
        var playbackStartedAtElapsed: Long = 0L
    ) {
        fun latencySummary(): String {
            fun s(a: Long, b: Long) = if (a > 0 && b > 0) "${(b - a) / 1000.0}s" else "—"
            return "gen=$generation " +
                "hint→send=${s(hintSentAtElapsed, messageSentAtElapsed)} " +
                "send→claim=${s(messageSentAtElapsed, claimedAtElapsed)} " +
                "claim→reply=${s(claimedAtElapsed, repliedAtElapsed)} " +
                "reply→audio=${s(repliedAtElapsed, audioReadyAtElapsed)} " +
                "audio→play=${s(audioReadyAtElapsed, playbackStartedAtElapsed)}"
        }
    }

    /** Monotonic generation — the single authority on "which turn is current". */
    private var conversationGeneration = 0L

    /** The turn currently owning the mic/player/UI (null when idle/stopped). */
    private var activeTurn: Turn? = null

    /**
     * V1: monotonic deadline for the follow-up window, independent of
     * VoiceState. onEndOfSpeech() may flip the state to TRANSCRIBING, but
     * the window stays open until THIS deadline passes. onError() retries
     * recoverable errors against this deadline, not the state label.
     */
    private var conversationDeadlineElapsed = 0L

    /** messageId → generation that sent it (for WS/poll-delivered replies). */
    private val messageGenerations = mutableMapOf<String, Long>()

    /** V1: count duplicate wake_requested WS events per message (log only). */
    private val wakeRequestedCounts = mutableMapOf<String, Int>()

    @Synchronized
    private fun rememberMessageGeneration(id: String, gen: Long) {
        messageGenerations[id] = gen
        if (messageGenerations.size > 100) {
            messageGenerations.remove(messageGenerations.keys.first())
        }
    }

    @Synchronized
    private fun generationForMessage(id: String): Long? = messageGenerations[id]

    /** True while the follow-up window is open, regardless of VoiceState. */
    private fun isFollowUpWindowOpen(): Boolean =
        conversationDeadlineElapsed > 0L &&
            SystemClock.elapsedRealtime() < conversationDeadlineElapsed

    /**
     * V1: cancel everything the current turn owns, immediately.
     * Stops/releases the player, cancels queued audio fetch, timers, and
     * marks the turn dead so in-flight callbacks fail their generation
     * check. Does NOT advance the generation — the caller does that when
     * starting the replacement turn (or on Stop).
     */
    private fun cancelActiveTurn(reason: String) {
        val turn = activeTurn
        logEvent("TURN CANCEL — reason=$reason gen=${turn?.generation ?: "none"}")
        // 1. Revoke playback eligibility NOW — never wait for callbacks.
        releasePlayer()
        audioStatus = ""
        // 2. Cancel any queued audio fetch.
        audioWaitMessageId = null
        synchronized(audioSignal) { audioSignal.notifyAll() }
        // 3. Cancel timers and close the follow-up window.
        cancelFailsafe()
        cancelConversationTimer()
        conversationDeadlineElapsed = 0L
        conversationEndsAtMs = 0L
        activeTurn = null
        // Background poll threads check their captured generation each
        // iteration and exit on mismatch — no separate interrupt needed.
    }

    // ------------------------------------------------------------------
    // M2.6: early wake hint — tell the relay the moment "Hi Natalie" is
    // detected so the server-side agent can warm up during speech capture
    // + transcription (~5-15s overlap). Rate-limited to 1 per 30s.
    // ------------------------------------------------------------------

    /** Timestamp (ms) of the last wake hint sent, for rate limiting. */
    private val lastWakeHintAt = java.util.concurrent.atomic.AtomicLong(0)

    // ------------------------------------------------------------------
    // M2.4: hands-free support — WakeLock + screen on/off tracking.
    // A partial WakeLock keeps the CPU awake so the openWakeWord audio
    // thread keeps streaming while the screen is off. It is held only
    // while hands-free mode is ON and released the moment it is off.
    // ------------------------------------------------------------------
    private var wakeLock: PowerManager.WakeLock? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> logEvent(
                    "SCREEN OFF — wake pipeline continues " +
                        "(hands-free=$handsFreeEnabled, " +
                        "wakeLockHeld=${wakeLock?.isHeld == true})"
                )
                Intent.ACTION_SCREEN_ON -> logEvent("SCREEN ON")
            }
        }
    }
    private var screenReceiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        checkSttAvailability()
        // M2.4: track screen on/off for the event log (never silent about
        // whether the wake pipeline survives screen-off).
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            registerReceiver(screenReceiver, filter)
            screenReceiverRegistered = true
        } catch (e: Exception) {
            logEvent("Screen receiver registration failed: ${e.message}")
        }
    }

    /** Check once at startup whether on-device/cloud STT exists. Never silent. */
    private fun checkSttAvailability() {
        try {
            val available = SpeechRecognizer.isRecognitionAvailable(this)
            sttAvailable = available
            sttEngineInfo = if (available) {
                "SpeechRecognizer available" +
                    (if (Config.STT_PREFER_OFFLINE) " (prefer offline)" else "")
            } else {
                "SpeechRecognizer NOT available on this device"
            }
            logEvent("STT check: $sttEngineInfo")
            if (!available) {
                logEvent("STT UNAVAILABLE — utterance capture will be skipped " +
                    "(wake-word detection still works)")
            }
        } catch (e: Exception) {
            sttAvailable = false
            sttEngineInfo = "STT check failed: ${e.javaClass.simpleName}: ${e.message}"
            logEvent("STT check FAILED: $sttEngineInfo")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startListening()
            ACTION_STOP -> stopListening()
            ACTION_SET_HANDSFREE -> {
                val enabled = intent.getBooleanExtra(EXTRA_HANDSFREE_ENABLED, false)
                applyHandsFreeMode(enabled)
            }
        }
        // Do not automatically resurrect a microphone service after a system kill.
        return START_NOT_STICKY
    }

    /**
     * M2.4: the app's task was swiped away. A foreground service survives
     * this — the wake pipeline keeps running. We do NOT stop ourselves here.
     * (If the user force-stops via Android's Active apps / app settings, the
     * system kills the process; we respect that and do not resurrect.)
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        logEvent("TASK REMOVED (app swiped away) — service continues in " +
            "background (hands-free=$handsFreeEnabled). " +
            "Force-stop via system settings is respected and will end listening.")
    }

    private fun startListening() {
        if (running) {
            logEvent("startListening: already running, ignoring")
            return
        }
        setState(VoiceState.STARTING)
        logEvent("Starting foreground service with microphone type")

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        running = true
        serviceRunning = true
        try {
            logEvent("Initializing openWakeWord (model=${Config.TEST_KEYWORD_LABEL}, " +
                "threshold=${Config.DETECTION_THRESHOLD})...")
            val det = OpenWakeWord.Builder(applicationContext)
                .setModel(OpenWakeWord.BuiltInModel.HI_NATALIE)
                .setThreshold(Config.DETECTION_THRESHOLD)
                .setDebounceMs(Config.DEBOUNCE_MS)
                .build()
            detector = det
            logEvent("openWakeWord ready — 3-stage ONNX pipeline on-device, " +
                "no network, no API key")

            // Start detection. Callback fires on the main thread.
            det.start { score ->
                if (running) {
                    onWakeDetected(score)
                }
            }

            // M2.5: bring up the WebSocket push transport alongside the
            // wake pipeline (independent of it — it reconnects on its own).
            startRelayWs()

            verifyWakeIdle()
        } catch (e: Exception) {
            logEvent("ERROR initializing openWakeWord: " +
                "${e.javaClass.simpleName}: ${e.message}")
            setState(VoiceState.PAUSED_ERROR)
            cleanup()
        }
    }

    /**
     * Verify the wake thread on a background worker (never block main).
     * NOTE: WAKE_IDLE is set only after the background thread proves
     * it is actually capturing audio — the "listening" status must be
     * verified, not assumed.
     */
    private fun verifyWakeIdle() {
        Thread({
            var verified = false
            var failMsg: String? = null
            for (i in 1..40) {
                Thread.sleep(250)
                val d = com.openwakeword.OpenWakeWord.Diag
                if (d.threadFailed != null || d.modelsError != null ||
                    d.audioRecordError != null || d.lastError != null) {
                    failMsg = (d.threadFailed ?: d.modelsError
                        ?: d.audioRecordError ?: d.lastError) +
                        " @ ${d.lastErrorStage}"
                    break
                }
                if (!running) return@Thread
                if (d.recordingStarted && d.framesRead > 5) {
                    verified = true
                    break
                }
            }
            if (!running) return@Thread
            if (failMsg != null) {
                logEvent("WAKE THREAD FAILED: $failMsg")
                setState(VoiceState.PAUSED_ERROR)
                cleanup()
                return@Thread
            }
            if (!verified) {
                logEvent("WAKE THREAD UNVERIFIED after 10s — " +
                    com.openwakeword.OpenWakeWord.Diag.summary()
                        .replace("\n", " | "))
                setState(VoiceState.PAUSED_ERROR)
                cleanup()
                return@Thread
            }
            setState(VoiceState.WAKE_IDLE)
            logEvent("WAKE_IDLE — VERIFIED listening for " +
                "'${Config.TEST_KEYWORD_LABEL}'. Thread alive, AudioRecord streaming.")
            // M2.4: if the persisted hands-free pref is ON (e.g. service was
            // started via the toggle, or restarted after a system kill),
            // acquire the WakeLock and reflect it in the notification.
            if (readHandsFreePref(this@ListeningForegroundService)) {
                handsFreeEnabled = true
                acquireWakeLock()
                updateNotification()
                logEvent("HANDS-FREE restored from preference — " +
                    "WakeLock acquired, persistent listening active")
            }
            // M2.3-fix: surface any replies that arrived while the service
            // was stopped (e.g. user tapped Stop during WAITING_REPLY).
            recoverMissedReplies()
        }, "NatalieVerifyThread").start()
    }

    /**
     * Verify the wake pipeline recovered after a transcription session:
     * fresh frames must arrive from the resumed AudioRecord.
     */
    private fun verifyWakeIdleAfterResume() {
        Thread({
            var verified = false
            for (i in 1..40) {
                Thread.sleep(250)
                if (!running) return@Thread
                // After resumeAudio() the frame counter restarts at 0.
                val d = com.openwakeword.OpenWakeWord.Diag
                if (d.recordingStarted && d.framesRead > 5) {
                    verified = true
                    break
                }
                // Only WAKE_IDLE-family states are valid here; bail on error.
                if (currentState.get() == VoiceState.PAUSED_ERROR) return@Thread
            }
            if (!running) return@Thread
            if (verified) {
                setState(VoiceState.WAKE_IDLE)
                logEvent("WAKE_IDLE — VERIFIED after transcription. " +
                    "Listening for '${Config.TEST_KEYWORD_LABEL}'.")
            } else {
                logEvent("WAKE RESUME UNVERIFIED after 10s — " +
                    com.openwakeword.OpenWakeWord.Diag.summary()
                        .replace("\n", " | "))
                setState(VoiceState.PAUSED_ERROR)
                cleanup()
            }
        }, "NatalieResumeVerifyThread").start()
    }

    private fun stopListening() {
        logEvent("Stop requested — releasing microphone")
        running = false
        // V1: explicit Stop is a session reset — cancel the turn and advance
        // the generation so every in-flight callback fails its ownership
        // check. Nothing stale may speak, poll, or reopen listening after.
        cancelActiveTurn("stop")
        conversationGeneration++
        cancelFailsafe()
        cancelConversationTimer()
        // An explicit Stop also ends hands-free mode — the user is done.
        if (handsFreeEnabled) {
            handsFreeEnabled = false
            getSharedPreferences(Config.UI_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(Config.HANDSFREE_ENABLED_KEY, false).apply()
            logEvent("HANDS-FREE MODE OFF (via Stop) — toggle cleared")
        }
        releaseWakeLock()
        // SpeechRecognizer + detector stop are synchronous; keep off main thread.
        Thread({
            try { speechRecognizer?.cancel() } catch (_: Exception) {}
            try { detector?.stop() } catch (_: Exception) {}
            cleanup()
        }, "NatalieStopThread").start()
    }

    // ------------------------------------------------------------------
    // M2.4: hands-free mode — persistent always-on listening.
    //
    // ON:  the foreground service + openWakeWord pipeline stay hot;
    //      a partial WakeLock keeps the CPU awake so audio keeps
    //      streaming with the screen off / phone locked; the
    //      notification reflects hands-free state with a Stop action.
    // OFF: WakeLock released; manual Start/Stop behavior as before.
    //
    // Deliberately NOT done: auto-start on BOOT_COMPLETED (mic foreground
    // services cannot launch from boot), WorkManager (15-min minimum,
    // wrong tool for continuous listening), silent restart after a
    // user force-stop (their stop is respected).
    // ------------------------------------------------------------------

    private fun applyHandsFreeMode(enabled: Boolean) {
        handsFreeEnabled = enabled
        if (enabled) {
            logEvent("HANDS-FREE MODE ON — persistent listening requested")
            if (!running) {
                // Service wasn't up yet (fresh toggle): start the pipeline.
                // startListening() verifies WAKE_IDLE, then restores the
                // WakeLock from the persisted pref (see verifyWakeIdle).
                logEvent("HANDS-FREE — service not running, starting pipeline")
                startListening()
            } else {
                acquireWakeLock()
                updateNotification()
            }
            logEvent("HANDS-FREE TIP (Samsung): add Natalie to Settings → " +
                "Battery → Background usage limits → Never sleeping apps, " +
                "or the system may kill background listening.")
        } else {
            releaseWakeLock()
            updateNotification()
            logEvent("HANDS-FREE MODE OFF — manual Start/Stop behavior")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            logEvent("HANDS-FREE — WakeLock already held")
            return
        }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                Config.HANDSFREE_WAKELOCK_TAG
            ).apply { acquire() }
            logEvent("HANDS-FREE — partial WakeLock ACQUIRED " +
                "(CPU stays awake for wake pipeline during screen-off)")
        } catch (e: Exception) {
            logEvent("HANDS-FREE — WakeLock acquire FAILED: " +
                "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        val wl = wakeLock
        wakeLock = null
        try {
            if (wl?.isHeld == true) {
                wl.release()
                logEvent("HANDS-FREE — WakeLock RELEASED")
            }
        } catch (e: Exception) {
            logEvent("HANDS-FREE — WakeLock release failed: ${e.message}")
        }
    }

    /** Re-issue the foreground notification with the current hands-free state. */
    private fun updateNotification() {
        if (!running) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification())
            logEvent("HANDS-FREE — notification updated " +
                "(hands-free=$handsFreeEnabled)")
        } catch (e: Exception) {
            logEvent("HANDS-FREE — notification update failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Wake → capture → transcribe (M2.1)
    // ------------------------------------------------------------------

    private fun onWakeDetected(score: Float) {
        // Guard: only WAKE_IDLE may start a session (no re-entry from
        // CAPTURING/TRANSCRIBING — the detector is paused there anyway).
        if (currentState.get() != VoiceState.WAKE_IDLE) {
            logEvent("Wake ignored — not in WAKE_IDLE " +
                "(state=${currentState.get()})")
            return
        }
        // V1: a genuinely new user turn supersedes everything. Cancel the
        // old turn's resources first, then advance the generation so any
        // in-flight callbacks from the old turn fail their ownership check.
        cancelActiveTurn("new-wake-turn")
        conversationGeneration++
        val turnId = java.util.UUID.randomUUID().toString()
        activeTurn = Turn(
            turnId = turnId,
            generation = conversationGeneration,
            messageId = null,
            startedAtElapsed = SystemClock.elapsedRealtime()
        )
        logEvent("TURN START gen=$conversationGeneration id=${turnId.take(8)}")
        wakeCount++
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        setState(VoiceState.WAKE_DETECTED)
        logEvent("*** WAKE DETECTED #$wakeCount at $ts " +
            "('${Config.TEST_KEYWORD_LABEL}', score=${"%.3f".format(score)}) ***")

        // M2.6: early wake hint — fire immediately on a background thread so
        // the server-side agent can warm up while Kareem is still speaking.
        // This overlaps agent activation with capture + transcription.
        Thread {
            try {
                val nowMs = System.currentTimeMillis()
                val last = lastWakeHintAt.get()
                if (nowMs - last < 30_000) {
                    logEvent("WAKE_HINT skipped — rate limited " +
                        "(${(nowMs - last) / 1000}s since last)")
                    return@Thread
                }
                lastWakeHintAt.set(nowMs)
                val result = RelayClient.sendWakeHint()
                if (result.ok) {
                    activeTurn?.hintSentAtElapsed = SystemClock.elapsedRealtime()
                    logEvent("WAKE_HINT SENT — server warmup triggered " +
                        "(http=${result.httpCode})")
                } else {
                    logEvent("WAKE_HINT failed: ${result.error}")
                }
            } catch (e: Exception) {
                logEvent("WAKE_HINT exception: ${e.message}")
            }
        }.apply { name = "NatalieWakeHint" }.start()

        if (Config.WAKE_CHIME_ENABLED) {
            try {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
                // Release after the tone finishes
                Thread { Thread.sleep(500); tone.release() }.start()
            } catch (e: Exception) {
                logEvent("Chime failed: ${e.message}")
            }
        }

        utterance = UtteranceSession(wakeTimestamp = ts, wakeScore = score)
        beginUtteranceCapture()
    }

    /**
     * CAPTURING: pause the wake pipeline (mic freed, no self-triggering),
     * then hand the mic to SpeechRecognizer for the utterance.
     */
    private fun beginUtteranceCapture() {
        lastPartialText = "" // fresh utterance, no stale partials
        val det = detector
        if (det == null) {
            logEvent("CAPTURING aborted — detector is null")
            setState(VoiceState.WAKE_IDLE)
            return
        }
        if (sttAvailable != true) {
            // Loud, not silent: STT was already reported at startup.
            logEvent("CAPTURING skipped — STT unavailable on this device " +
                "($sttEngineInfo). Back to WAKE_IDLE.")
            latestTranscriptError = "STT unavailable on this device"
            setState(VoiceState.WAKE_IDLE)
            return
        }

        setState(VoiceState.CAPTURING)
        utterance?.captureStartElapsed = System.currentTimeMillis()
        logEvent("CAPTURING — pausing wake pipeline, handing mic to SpeechRecognizer")

        // 1. No self-triggering: classifier off first...
        det.setDetectionEnabled(false)
        // 2. ...then free the mic (models stay loaded for fast resume).
        det.pauseAudio()

        if (det.isAudioRunning()) {
            logEvent("CAPTURING WARNING — wake AudioRecord still running; " +
                "SpeechRecognizer may fail to acquire the mic")
        } else {
            logEvent("CAPTURING — wake AudioRecord released, mic free")
        }

        startSpeechRecognition()
    }

    private fun startSpeechRecognition() {
        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(recognitionListener)
                }
                logEvent("SpeechRecognizer created")
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, Config.STT_PREFER_OFFLINE)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // M2 VAD parameters → recognizer endpointing extras.
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    Config.STT_COMPLETE_SILENCE_MS
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    Config.STT_POSSIBLY_COMPLETE_SILENCE_MS
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    Config.STT_MINIMUM_LENGTH_MS
                )
            }
            logEvent("CAPTURING — SpeechRecognizer.startListening " +
                "(preferOffline=${Config.STT_PREFER_OFFLINE}, " +
                "silenceMs=${Config.STT_COMPLETE_SILENCE_MS})")
            speechRecognizer?.startListening(intent)
            armFailsafe()
        } catch (e: Exception) {
            logEvent("CAPTURING FAILED to start SpeechRecognizer: " +
                "${e.javaClass.simpleName}: ${e.message}")
            utterance?.error = "recognizer start failed: ${e.message}"
            finishUtterance()
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            // CAPTURING is only "real" once the recognizer confirms the mic.
            utterance?.readyForSpeechElapsed = System.currentTimeMillis()
            logEvent("CAPTURING confirmed — SpeechRecognizer ready, mic live. Speak now.")
        }

        override fun onBeginningOfSpeech() {
            utterance?.speechBeginElapsed = System.currentTimeMillis()
            logEvent("Speech started (beginning of utterance)")
        }

        override fun onRmsChanged(rmsdB: Float) {
            val u = utterance ?: return
            if (rmsdB < u.rmsMin) u.rmsMin = rmsdB
            if (rmsdB > u.rmsMax) u.rmsMax = rmsdB
            u.rmsSamples++
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            utterance?.speechEndElapsed = System.currentTimeMillis()
            // TRANSCRIBING is real: speech ended, results incoming.
            setState(VoiceState.TRANSCRIBING)
            logEvent("TRANSCRIBING — end of speech, waiting for results")
        }

        override fun onError(error: Int) {
            cancelFailsafe()
            val reason = sttErrorName(error)
            logEvent("STT ERROR: $reason (code=$error)")
            // V1: the follow-up window is tracked by monotonic deadline,
            // INDEPENDENT of VoiceState. onEndOfSpeech() may have already
            // flipped the state to TRANSCRIBING — that must not bypass the
            // retry. Only recoverable errors retry: no-match (7) and
            // speech-timeout (6). Anything else (audio, client, network,
            // permissions, busy, server) closes the window immediately.
            val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (isFollowUpWindowOpen() && recoverable) {
                utterance?.let {
                    it.resultElapsed = System.currentTimeMillis()
                    it.error = reason
                }
                if (running && conversationRestarts < 8) {
                    conversationRestarts++
                    logEvent("CONVERSATION — recognizer cycled ($reason), " +
                        "window still open (restart #$conversationRestarts)")
                    lastPartialText = ""
                    // Brief beat before re-listening; never a tight loop.
                    // V1: recheck the DEADLINE (not the state) inside the
                    // delayed callback — the window may have expired while
                    // we waited.
                    mainHandler.postDelayed({
                        if (isFollowUpWindowOpen() && running) {
                            startSpeechRecognition()
                        } else {
                            logEvent("CONVERSATION — window expired during " +
                                "retry beat, returning to standby")
                            closeFollowUpWindow()
                        }
                    }, 500)
                } else {
                    logEvent("CONVERSATION — restart limit hit or stopping, " +
                        "returning to standby")
                    closeFollowUpWindow()
                }
                return
            }
            // Not a recoverable follow-up error (or window already closed):
            // end the window if one was open, then the normal error path.
            if (conversationDeadlineElapsed > 0L) {
                logEvent("CONVERSATION — unrecoverable error ($reason) or " +
                    "window expired, closing follow-up window")
                closeFollowUpWindow()
            }
            utterance?.let {
                it.resultElapsed = System.currentTimeMillis()
                it.error = reason
            }
            latestTranscriptError = reason
            latestTranscript = ""
            finishUtterance()
        }

        override fun onResults(results: Bundle?) {
            cancelFailsafe()
            // V1: a follow-up arrived inside the conversation window —
            // this is a GENUINELY NEW turn: cancel the old turn, advance
            // the generation, and start fresh. No wake word was needed.
            if (isFollowUpWindowOpen()) {
                cancelConversationTimer()
                conversationDeadlineElapsed = 0L
                conversationEndsAtMs = 0L
                logEvent("CONVERSATION — follow-up captured, sending to relay")
                cancelActiveTurn("follow-up-turn")
                conversationGeneration++
                val turnId = java.util.UUID.randomUUID().toString()
                activeTurn = Turn(
                    turnId = turnId,
                    generation = conversationGeneration,
                    messageId = null,
                    startedAtElapsed = SystemClock.elapsedRealtime()
                )
                logEvent("TURN START gen=$conversationGeneration id=${turnId.take(8)} (follow-up)")
            }
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            var text = matches?.firstOrNull()?.trim() ?: ""
            // Android quirk: final results can come back empty even when
            // partials captured the utterance perfectly. Fall back to the
            // last partial rather than reporting "empty result".
            var usedPartialFallback = false
            if (text.isEmpty() && lastPartialText.isNotEmpty()) {
                text = lastPartialText
                usedPartialFallback = true
                logEvent("Final results empty — using last partial as transcript")
            }
            val u = utterance
            u?.resultElapsed = System.currentTimeMillis()
            if (text.isNotEmpty()) {
                utteranceCount++
                u?.transcript = text
                latestTranscript = text
                latestTranscriptTime =
                    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                latestTranscriptError = ""
                val latencyMs = (u?.resultElapsed ?: 0L) - (u?.captureStartElapsed ?: 0L)
                logEvent("TRANSCRIPT #$utteranceCount (sttLatencyMs=$latencyMs): \"$text\"")
                // M2.2: hand the transcript to the relay instead of going
                // straight back to WAKE_IDLE. sendToRelay() owns the rest of
                // the flow and calls finishUtterance() when done.
                setState(VoiceState.TRANSCRIBING)
                sendToRelay(text)
            } else {
                u?.error = "empty result"
                latestTranscriptError = "empty result"
                logEvent("STT returned empty results")
                setState(VoiceState.TRANSCRIBING)
                finishUtterance()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches =
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim()
            if (!text.isNullOrEmpty()) {
                lastPartialText = text
                logEvent("Partial: \"$text\"")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun sttErrorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "missing RECORD_AUDIO permission"
        SpeechRecognizer.ERROR_NETWORK -> "network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no speech recognized"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech input (timeout)"
        else -> "unknown error ($code)"
    }

    // ------------------------------------------------------------------
    // M2.2: Cloudflare relay — transcript → Natalie text reply
    //
    // Flow: onResults() → sendToRelay() → SENDING (POST, background thread)
    //   → WAITING_REPLY (poll loop, background thread)
    //   → REPLY_RECEIVED → finishUtterance() → WAKE_IDLE.
    //
    // State transitions are marshalled to the main thread via mainHandler.
    // Network I/O never touches the main thread. The reply cursor is
    // persisted in SharedPreferences so restarts don't miss or replay.
    // ------------------------------------------------------------------

    private fun relayPrefs() =
        getSharedPreferences(Config.RELAY_PREFS, Context.MODE_PRIVATE)

    private fun loadReplyCursor(): Long =
        relayPrefs().getLong(Config.RELAY_CURSOR_KEY, 0L)

    private fun saveReplyCursor(cursor: Long) {
        relayPrefs().edit().putLong(Config.RELAY_CURSOR_KEY, cursor).apply()
    }

    // ------------------------------------------------------------------
    // M2.3-fix: pending sent-message tracking (reply recovery across restarts)
    //
    // Every message this device POSTs is recorded as "uuid:sendCursor"
    // until its reply is displayed. If the service stops mid-poll (user
    // taps Stop), the entry survives; the next startup re-polls the relay
    // from the earliest pending send-cursor and surfaces any replies that
    // arrived in the meantime. A reply is only ever removed from pending
    // when it is actually displayed — never on abort or timeout.
    // ------------------------------------------------------------------

    /** Pending sent messages: messageId → reply cursor at send time. */
    private fun getPendingMessageIds(): Map<String, Long> {
        val raw = relayPrefs().getStringSet(Config.PENDING_IDS_KEY, emptySet())
            ?: emptySet()
        val out = mutableMapOf<String, Long>()
        for (entry in raw) {
            val sep = entry.lastIndexOf(':')
            if (sep > 0) {
                val id = entry.substring(0, sep)
                val cursor = entry.substring(sep + 1).toLongOrNull() ?: 0L
                out[id] = cursor
            }
        }
        return out
    }

    private fun addPendingMessageId(id: String) {
        val raw = HashSet(
            relayPrefs().getStringSet(Config.PENDING_IDS_KEY, emptySet())
                ?: emptySet()
        )
        raw.add("$id:${loadReplyCursor()}")
        relayPrefs().edit().putStringSet(Config.PENDING_IDS_KEY, raw).apply()
        logEvent("RECOVERY TRACK — id=$id added to pending")
    }

    private fun removePendingMessageId(id: String) {
        val raw = HashSet(
            relayPrefs().getStringSet(Config.PENDING_IDS_KEY, emptySet())
                ?: emptySet()
        )
        val removed = raw.removeAll { it.startsWith("$id:") }
        if (removed) {
            relayPrefs().edit().putStringSet(Config.PENDING_IDS_KEY, raw).apply()
            logEvent("RECOVERY TRACK — id=$id removed from pending")
        }
    }

    private fun setStateOnMain(s: VoiceState) {
        if (Looper.myLooper() == Looper.getMainLooper()) setState(s)
        else mainHandler.post { setState(s) }
    }

    // ------------------------------------------------------------------
    // M2.5: WebSocket push transport.
    //
    // The WebSocket runs alongside the existing HTTP poll loop. Both
    // race to deliver each reply; claimReply() guarantees exactly one
    // winner, and handleIncomingReply() is the single choke point that
    // displays the reply whichever transport won. The poll loop is the
    // live fallback — if the socket drops, replies still arrive via poll
    // (each delivery logs its transport for the debug status line).
    // ------------------------------------------------------------------

    /** Dedup gate: true for the first claimer of a message ID, false after. */
    @Synchronized
    private fun claimReply(messageId: String): Boolean {
        if (handledReplyIds.contains(messageId)) return false
        handledReplyIds.add(messageId)
        // Bound the set; LinkedHashSet iteration order is oldest-first.
        if (handledReplyIds.size > 100) {
            handledReplyIds.remove(handledReplyIds.first())
        }
        return true
    }

    /**
     * Display a reply — whichever transport delivered it. Runs on the main
     * thread. The caller MUST have won claimReply() first.
     *
     * V1: every reply is bound to the generation that sent its message.
     * A stale reply (superseded turn) is logged and dropped — it must never
     * start playback, overwrite the current reply panel, or reopen the
     * conversation window. Startup recovery passes isRecovery=true: the old
     * text may be shown, but it never auto-speaks (Req 4).
     */
    private fun handleIncomingReply(
        messageId: String,
        body: String,
        transport: String,
        turnGeneration: Long,
        isRecovery: Boolean = false
    ) {
        // V1: stale-turn guard. A delayed reply from a superseded turn must
        // not touch the live conversation — not the UI, not the player.
        if (!isRecovery && turnGeneration != conversationGeneration) {
            logEvent("STALE REPLY dropped — id=$messageId transport=$transport " +
                "reply gen=$turnGeneration, current gen=$conversationGeneration")
            return
        }
        removePendingMessageId(messageId)
        replyCount++
        latestReply = body
        latestReplyTime = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        latestReplyError = ""
        lastReplyTransport = transport
        logEvent("REPLY via $transport — displaying (id=$messageId gen=$turnGeneration)")
        if (isRecovery) {
            // V1 Req 4: a recovered old reply appears as text only — it must
            // never auto-speak. The wake pipeline was never paused for it.
            logEvent("RECOVERY — text shown, no audio playback for stale reply")
            setState(VoiceState.REPLY_RECEIVED)
            finishUtterance(enterConversation = false)
            return
        }
        // Record reply-arrival latency on the owning turn (if still current).
        if (turnGeneration == conversationGeneration) {
            activeTurn?.repliedAtElapsed = SystemClock.elapsedRealtime()
        }
        setState(VoiceState.REPLY_RECEIVED)
        // M2.3: fetch Vanessa's voice before the conversation pause.
        // finishUtterance(enterConversation=true) is deferred until
        // playback completes (or audio times out) — the wake pipeline
        // stays paused while she speaks.
        fetchAndPlayAudio(messageId, turnGeneration)
    }

    private val wsListener = object : RelayWebSocket.Listener {
        override fun onWsReply(messageId: String, body: String) {
            mainHandler.post {
                if (!running) return@post
                if (!getPendingMessageIds().containsKey(messageId)) {
                    logEvent("WS REPLY ignored — id=$messageId not in pending set")
                    return@post
                }
                if (!claimReply(messageId)) {
                    logEvent("WS REPLY duplicate — id=$messageId already handled")
                    return@post
                }
                // V1: bind to the generation that sent this message. If the
                // mapping is gone (very old), treat as stale — never assign
                // a delayed reply the CURRENT generation.
                val gen = generationForMessage(messageId)
                if (gen == null) {
                    logEvent("WS REPLY — id=$messageId has no generation mapping, " +
                        "treating as stale")
                    return@post
                }
                handleIncomingReply(messageId, body, "ws", gen)
            }
        }

        override fun onWsAudioReady(messageId: String) {
            logEvent("WS AUDIO-READY id=$messageId " +
                "(waiting for $audioWaitMessageId) — waking audio fetch now")
            // Wake the audio poll thread immediately instead of waiting
            // out the 2s poll interval.
            synchronized(audioSignal) { audioSignal.notifyAll() }
        }

        override fun onWsStatusEvent(json: JSONObject) {
            val status = json.optString("status", "?")
            val msgId = json.optString("message_id", "?")
            logEvent("WS EVENT — status=$status id=$msgId")
            // V1 Req 5/6: per-message status dedup + latency instrumentation.
            // Duplicate wake_requested events are EXPECTED (the hook fires
            // repeatedly) — they are status notifications, not new turns, and
            // must never advance the generation. We count them per message
            // for the log; single-activation enforcement lives server-side.
            if (status == "wake_requested") {
                val n = wakeRequestedCounts.getOrDefault(msgId, 0) + 1
                wakeRequestedCounts[msgId] = n
                if (n > 1) {
                    logEvent("WS EVENT — wake_requested #$n for id=$msgId " +
                        "(duplicate status, not a new turn)")
                }
                return
            }
            if (status == "claimed") {
                // V1 Req 6: record claim time on the owning turn for the
                // latency breakdown (hint→send→claim→reply→audio→playback).
                val gen = generationForMessage(msgId)
                if (gen != null && gen == conversationGeneration) {
                    activeTurn?.claimedAtElapsed = SystemClock.elapsedRealtime()
                    logEvent("TURN CLAIMED — id=$msgId gen=$gen")
                } else {
                    logEvent("WS EVENT — claimed for id=$msgId " +
                        "(gen=$gen, current=$conversationGeneration)")
                }
            }
        }

        override fun onWsConnected(serverReplyCursor: Long) {
            wsStatus = "up"
            logEvent("WS CONNECTED — hello reply_cursor=$serverReplyCursor " +
                "(local=${loadReplyCursor()})")
            catchUpFromCursor(serverReplyCursor)
        }

        override fun onWsDisconnected(reason: String) {
            wsStatus = "down"
            logEvent("WS DISCONNECTED — $reason " +
                "(reconnect scheduled; HTTP poll continues as fallback)")
        }
    }

    private fun startRelayWs() {
        if (relayWs != null) {
            logEvent("WS already exists, not starting a second one")
            return
        }
        logEvent("WS starting push transport (wss, phone role)")
        relayWs = RelayWebSocket(wsListener).also { it.start() }
    }

    private fun stopRelayWs() {
        try {
            relayWs?.shutdown()
        } catch (_: Exception) { }
        relayWs = null
        wsStatus = "down"
    }

    /**
     * On (re)connect, the hello message carries the server's reply cursor.
     * If it is ahead of our local cursor, HTTP-poll the gap for replies to
     * our pending messages — this catches anything that arrived while the
     * socket was down. claimReply() dedups against the live poll loop and
     * startup recovery.
     */
    private fun catchUpFromCursor(serverReplyCursor: Long) {
        Thread({
            val local = loadReplyCursor()
            if (serverReplyCursor < 0 || serverReplyCursor <= local) {
                logEvent("WS CATCH-UP — nothing new " +
                    "(server=$serverReplyCursor local=$local)")
                return@Thread
            }
            val pending = getPendingMessageIds()
            if (pending.isEmpty()) {
                saveReplyCursor(serverReplyCursor)
                logEvent("WS CATCH-UP — no pending messages; " +
                    "cursor advanced to $serverReplyCursor")
                return@Thread
            }
            logEvent("WS CATCH-UP — polling from cursor $local " +
                "for ${pending.size} pending message(s)")
            var cursor = local
            while (true) {
                if (!running) {
                    logEvent("WS CATCH-UP ABORTED — service stopping")
                    return@Thread
                }
                val poll = RelayClient.pollReplies(cursor)
                if (poll.httpCode != 200) {
                    logEvent("WS CATCH-UP poll failed: " +
                        (poll.error ?: "HTTP ${poll.httpCode}"))
                    return@Thread
                }
                for (reply in poll.replies) {
                    if (pending.containsKey(reply.messageId) &&
                        claimReply(reply.messageId)) {
                        val id = reply.messageId
                        val body = reply.body
                        // V1: bind to the sending generation; drop if unmapped.
                        val gen = generationForMessage(id)
                        if (gen == null) {
                            logEvent("WS CATCH-UP — id=$id has no generation " +
                                "mapping, skipping")
                        } else {
                            mainHandler.post {
                                handleIncomingReply(id, body, "ws-catchup", gen)
                            }
                        }
                    }
                }
                if (poll.cursor > cursor) {
                    cursor = poll.cursor
                    saveReplyCursor(cursor)
                }
                if (!poll.hasMore) break
            }
            logEvent("WS CATCH-UP done (cursor=$cursor)")
        }, "NatalieWsCatchup").start()
    }

    /**
     * POST the transcript to the relay, then poll for Natalie's reply.
     * Called on the main thread from onResults(); all network work happens
     * on a dedicated background thread.
     */
    private fun sendToRelay(transcript: String) {
        val messageId = java.util.UUID.randomUUID().toString()
        // V1: capture this turn's generation NOW. The poll thread validates
        // it every iteration — a superseded turn's poll loop exits silently.
        val sendGeneration = conversationGeneration
        activeTurn?.let {
            it.messageId = messageId
            it.messageSentAtElapsed = SystemClock.elapsedRealtime()
        }
        rememberMessageGeneration(messageId, sendGeneration)
        val sendStartMs = System.currentTimeMillis()
        logEvent("RELAY SENDING — id=$messageId gen=$sendGeneration " +
            "transcript=\"${transcript.take(80)}\"")
        // Track this message until its reply is displayed — if the user
        // taps Stop mid-poll, startup recovery will pick it up later.
        addPendingMessageId(messageId)
        setStateOnMain(VoiceState.SENDING)

        Thread({
            // SENDING is real: the HTTP request is actually dispatched here.
            val post = RelayClient.postMessage(messageId, transcript)
            if (post.httpCode != 200 && post.httpCode != 201) {
                val err = post.error ?: "HTTP ${post.httpCode}"
                logEvent("RELAY SEND FAILED — id=$messageId $err")
                // Never reached the relay — no reply will ever come.
                removePendingMessageId(messageId)
                mainHandler.post {
                    latestReply = ""
                    latestReplyError = "send failed: $err"
                    finishUtterance()
                }
                return@Thread
            }
            logEvent("RELAY SENT — id=$messageId http=${post.httpCode} " +
                "seq=${post.seq} duplicate=${post.duplicate}")
            // WAITING_REPLY is real: the message was accepted by the relay.
            mainHandler.post {
                // M2.5: mark when the wait began (thinking indicator) —
                // the WebSocket may beat the poll loop; claimReply() dedups.
                waitingReplySinceMs = System.currentTimeMillis()
                setState(VoiceState.WAITING_REPLY)
            }
            logEvent("RELAY WAITING — polling for reply to id=$messageId " +
                "(cursor=${loadReplyCursor()})")

            // Poll loop: every 2s, generous 120s timeout (Muse can take tens
            // of seconds). Match reply by message_id — never a stale reply.
            val deadline = System.currentTimeMillis() + Config.RELAY_REPLY_TIMEOUT_MS
            var cursor = loadReplyCursor()
            var attempts = 0
            while (System.currentTimeMillis() < deadline) {
                // Bail if the service was stopped mid-wait.
                if (!running) {
                    logEvent("RELAY POLL ABORTED — service stopping")
                    return@Thread
                }
                // V1: this turn was superseded (new wake / follow-up / Stop).
                // Exit silently — the new turn owns the conversation now.
                if (sendGeneration != conversationGeneration) {
                    logEvent("RELAY POLL — id=$messageId generation superseded " +
                        "(sent gen=$sendGeneration, current=$conversationGeneration), " +
                        "stopping poll")
                    return@Thread
                }
                // M2.5: if another transport (WebSocket) already delivered
                // this reply, the pending entry is gone — stop polling
                // instead of running to the timeout and clobbering the UI.
                if (!getPendingMessageIds().containsKey(messageId)) {
                    logEvent("RELAY POLL — id=$messageId no longer pending " +
                        "(delivered via another transport), stopping poll")
                    return@Thread
                }
                attempts++
                val poll = RelayClient.pollReplies(cursor)
                if (poll.httpCode == 200) {
                    // Advance the persisted cursor past everything we've seen,
                    // even if our reply isn't among them yet.
                    if (poll.cursor > cursor) {
                        cursor = poll.cursor
                        saveReplyCursor(cursor)
                    }
                    val match = poll.replies.firstOrNull { it.messageId == messageId }
                    if (match != null) {
                        // M2.5: the WebSocket may have already delivered
                        // this reply — claimReply() lets exactly one
                        // transport win.
                        if (!claimReply(match.messageId)) {
                            logEvent("RELAY POLL — reply id=$messageId already " +
                                "handled via another transport, stopping poll")
                            return@Thread
                        }
                        val replyLatencyMs = System.currentTimeMillis() - sendStartMs
                        logEvent("RELAY REPLY #${replyCount + 1} — id=$messageId " +
                            "replySeq=${match.seq} latencyMs=$replyLatencyMs " +
                            "attempts=$attempts (transport: poll)")
                        logEvent("NATALIE SAYS: \"${match.body.take(200)}\"")
                        val body = match.body
                        mainHandler.post {
                            handleIncomingReply(messageId, body, "poll", sendGeneration)
                        }
                        return@Thread
                    }
                } else {
                    logEvent("RELAY POLL attempt=$attempts failed: " +
                        (poll.error ?: "HTTP ${poll.httpCode}"))
                }
                try {
                    Thread.sleep(Config.RELAY_POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    logEvent("RELAY POLL INTERRUPTED")
                    return@Thread
                }
            }
            // Timeout: no reply within the generous window. The pending entry
            // is deliberately LEFT in place — the reply may still arrive,
            // and startup recovery will surface it on the next Start.
            logEvent("RELAY TIMEOUT — no reply for id=$messageId after " +
                "${Config.RELAY_REPLY_TIMEOUT_MS}ms ($attempts attempts)")
            mainHandler.post {
                latestReply = ""
                latestReplyError = "no reply from Natalie (timeout)"
                finishUtterance()
            }
        }, "NatalieRelay").start()
    }

    // ------------------------------------------------------------------
    // M2.3-fix: startup recovery for missed replies.
    //
    // Scenario: the user taps Stop while WAITING_REPLY. The in-flight poll
    // is aborted ("RELAY POLL ABORTED"), but the reply later lands in the
    // relay. Previously the reply panel stayed empty forever. Now every
    // sent message stays in the pending set until its reply is displayed,
    // so the next Start re-polls from the earliest pending send-cursor
    // (with has_more pagination) and surfaces anything that arrived.
    //
    // The latest missed reply is displayed in the reply panel AND gets
    // Vanessa TTS playback, exactly like the normal flow. The wake
    // pipeline is paused first (same as CAPTURING) so she can't
    // self-trigger; finishUtterance() resumes it after playback.
    // ------------------------------------------------------------------

    private fun recoverMissedReplies() {
        Thread({
            val pending = getPendingMessageIds()
            if (pending.isEmpty()) {
                logEvent("RECOVERY — no pending sent messages, nothing to recover")
                return@Thread
            }
            val since = pending.values.minOrNull() ?: loadReplyCursor()
            logEvent("RECOVERY — checking ${pending.size} pending message(s) " +
                "for missed replies (from cursor=$since)")
            var cursor = since
            val matched = mutableListOf<RelayClient.Reply>()
            while (true) {
                if (!running) {
                    logEvent("RECOVERY ABORTED — service stopping")
                    return@Thread
                }
                val poll = RelayClient.pollReplies(cursor)
                if (poll.httpCode != 200) {
                    logEvent("RECOVERY FAILED — ${poll.error ?: "HTTP ${poll.httpCode}"}")
                    return@Thread
                }
                for (reply in poll.replies) {
                    if (pending.containsKey(reply.messageId)) {
                        matched.add(reply)
                    }
                }
                if (poll.cursor > cursor) {
                    cursor = poll.cursor
                    // Advance the persisted high-watermark past everything
                    // seen, so stale cursor state can't accumulate.
                    saveReplyCursor(cursor)
                }
                if (!poll.hasMore) break
            }
            if (matched.isEmpty()) {
                logEvent("RECOVERY — no missed replies arrived for pending messages")
                return@Thread
            }
            // Show the latest missed reply (highest seq); older ones are
            // stale conversation turns. All matched IDs leave pending —
            // they have been accounted for.
            val latest = matched.maxByOrNull { it.seq }!!
            for (m in matched) removePendingMessageId(m.messageId)
            logEvent("RECOVERY — found ${matched.size} missed reply(ies); " +
                "showing latest (replySeq=${latest.seq})")
            mainHandler.post { displayRecoveredReply(latest) }
        }, "NatalieRecovery").start()
    }

    /**
     * Display a reply recovered on startup. Runs on the main thread.
     * Pauses the wake pipeline first (same as CAPTURING) so Vanessa's
     * playback can't self-trigger a wake; the normal M2.3 flow
     * (fetchAndPlayAudio → SPEAKING → finishUtterance) resumes it after.
     */
    private fun displayRecoveredReply(reply: RelayClient.Reply) {
        val det = detector
        if (det != null && running && currentState.get() == VoiceState.WAKE_IDLE) {
            det.setDetectionEnabled(false)
            det.pauseAudio()
            logEvent("RECOVERY — wake pipeline paused for missed-reply playback")
        } else {
            logEvent("RECOVERY — wake pipeline not paused " +
                "(state=${currentState.get()}); showing reply anyway")
        }
        // M2.5: dedup against the WebSocket / live poll, then use the
        // single choke point. V1: recovery replies are text-only, never
        // auto-speak (isRecovery=true) — the turn they belonged to is long
        // gone and there is no live turn to bind them to.
        if (!claimReply(reply.messageId)) {
            logEvent("RECOVERY — reply id=${reply.messageId} already handled, skipping")
            return
        }
        logEvent("RECOVERY — displaying missed reply: \"${reply.body.take(200)}\"")
        handleIncomingReply(reply.messageId, reply.body, "recovery",
            turnGeneration = -1L, isRecovery = true)
    }

    // ------------------------------------------------------------------
    // M2.3: Vanessa TTS audio — fetch + playback (full voice loop)
    //
    // Flow: REPLY_RECEIVED → fetchAndPlayAudio() (background thread polls
    //   GET /v1/replies/{id}/audio every 2s, up to 60s)
    //   → audio arrives: Base64-decode → temp MP3 → MediaPlayer → SPEAKING
    //   → onCompletion/onError: release player → finishUtterance() → WAKE_IDLE
    //   → timeout: log AUDIO TIMEOUT, text reply stands as fallback.
    //
    // The wake pipeline stays paused the whole time (it was paused at
    // CAPTURING and is only resumed inside finishUtterance), so Vanessa
    // can never self-trigger a wake. One playback per message ID.
    // ------------------------------------------------------------------

    private fun fetchAndPlayAudio(messageId: String, turnGeneration: Long) {
        if (wasPlayed(messageId)) {
            logEvent("AUDIO SKIP — id=$messageId already played")
            finishUtterance()
            return
        }
        // V1: capture the owning turn's generation. Every loop iteration
        // validates it — a superseded turn's audio fetch exits silently.
        val audioGeneration = turnGeneration
        audioStatus = "waiting"
        // M2.5: remember which message we're waiting on so a WebSocket
        // audio-ready event can wake this loop immediately.
        audioWaitMessageId = messageId
        logEvent("AUDIO WAITING — polling for TTS for id=$messageId gen=$audioGeneration")
        Thread({
            val deadline = System.currentTimeMillis() + Config.AUDIO_TIMEOUT_MS
            var attempts = 0
            while (System.currentTimeMillis() < deadline) {
                if (!running) {
                    logEvent("AUDIO POLL ABORTED — service stopping")
                    audioWaitMessageId = null
                    return@Thread
                }
                // V1: superseded turn — stop polling, never play stale audio.
                if (audioGeneration != conversationGeneration) {
                    logEvent("AUDIO POLL — id=$messageId generation superseded " +
                        "(audio gen=$audioGeneration, current=$conversationGeneration), " +
                        "stopping without playback")
                    audioWaitMessageId = null
                    return@Thread
                }
                attempts++
                val res = RelayClient.fetchReplyAudio(messageId)
                if (res.httpCode == 200 && !res.audioBase64.isNullOrEmpty()) {
                    logEvent("AUDIO RECEIVED — id=$messageId " +
                        "base64Chars=${res.audioBase64.length} attempts=$attempts")
                    audioWaitMessageId = null
                    // V1: record audio-ready latency on the owning turn.
                    if (audioGeneration == conversationGeneration) {
                        activeTurn?.audioReadyAtElapsed = SystemClock.elapsedRealtime()
                    }
                    mainHandler.post { playReplyAudio(messageId, res.audioBase64, audioGeneration) }
                    return@Thread
                }
                if (res.httpCode != 404 && res.httpCode != 200) {
                    logEvent("AUDIO POLL attempt=$attempts failed: " +
                        (res.error ?: "HTTP ${res.httpCode}"))
                }
                // M2.5: wait on the signal instead of a blind sleep — a
                // WebSocket audio-ready event notifies immediately and
                // skips the rest of the 2s interval.
                synchronized(audioSignal) {
                    try {
                        audioSignal.wait(Config.AUDIO_POLL_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        logEvent("AUDIO POLL INTERRUPTED")
                        audioWaitMessageId = null
                        return@Thread
                    }
                }
            }
            logEvent("AUDIO TIMEOUT — no TTS for id=$messageId after " +
                "${Config.AUDIO_TIMEOUT_MS}ms ($attempts attempts); text reply stands")
            audioWaitMessageId = null
            mainHandler.post {
                // V1: a superseded turn's timeout must not reopen the
                // conversation window or touch the reply panel.
                if (audioGeneration != conversationGeneration) {
                    logEvent("AUDIO TIMEOUT — id=$messageId turn superseded, " +
                        "not touching state")
                    return@post
                }
                audioStatus = "no audio — text only"
                // M2.5: text stands as her turn — still enter conversation
                // mode so the rhythm holds even without voice.
                finishUtterance(enterConversation = true)
            }
        }, "NatalieAudio").start()
    }

    /**
     * Decode + play the MP3. Called on the main thread via mainHandler.
     *
     * V1: playback is bound to (turnGeneration, playerIdentity). A stale
     * turn's audio never starts. The completion/error callbacks capture the
     * MediaPlayer instance they belong to — a callback from an OLD player
     * must never stop a NEWER player or touch the new turn's state.
     */
    private fun playReplyAudio(messageId: String, audioBase64: String, turnGeneration: Long) {
        // V1: stale-turn guard — never start playback for a superseded turn.
        if (turnGeneration != conversationGeneration) {
            logEvent("AUDIO STALE — id=$messageId not playing " +
                "(audio gen=$turnGeneration, current=$conversationGeneration)")
            return
        }
        // Double-check on the UI thread: never play the same ID twice.
        if (wasPlayed(messageId)) {
            logEvent("AUDIO SKIP (race) — id=$messageId already played")
            finishUtterance()
            return
        }
        try {
            val bytes = Base64.decode(audioBase64, Base64.DEFAULT)
            logEvent("AUDIO DECODED — id=$messageId bytes=${bytes.size}")
            val file = File(cacheDir, "natalie_reply_${messageId.take(8)}.mp3")
            FileOutputStream(file).use { it.write(bytes) }

            releasePlayer()
            val mp = MediaPlayer()
            mediaPlayer = mp
            // V1: player identity — callbacks below act only if THIS player
            // is still the current one.
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                // V1: an old player's completion must never finish a newer
                // turn's utterance or reopen its conversation window.
                if (mediaPlayer !== it || turnGeneration != conversationGeneration) {
                    logEvent("AUDIO COMPLETED (stale player/turn) — id=$messageId " +
                        "ignoring callback")
                    try { it.release() } catch (_: Exception) {}
                    return@setOnCompletionListener
                }
                logEvent("AUDIO COMPLETED — id=$messageId playback finished")
                markPlayed(messageId)
                audioStatus = ""
                releasePlayer()
                try { file.delete() } catch (_: Exception) {}
                // Latency summary for this turn (Req 6).
                activeTurn?.let { logEvent("TURN LATENCY — ${it.latencySummary()}") }
                // M2.5: she finished speaking — open the conversation
                // window for a follow-up instead of going to standby.
                finishUtterance(enterConversation = true)
            }
            mp.setOnErrorListener { errPlayer, what, extra ->
                if (mediaPlayer !== errPlayer || turnGeneration != conversationGeneration) {
                    logEvent("AUDIO PLAYBACK FAILED (stale player/turn) — " +
                        "id=$messageId ignoring callback")
                    try { errPlayer.release() } catch (_: Exception) {}
                    return@setOnErrorListener true
                }
                logEvent("AUDIO PLAYBACK FAILED — id=$messageId what=$what extra=$extra")
                markPlayed(messageId)
                audioStatus = "playback error"
                releasePlayer()
                try { file.delete() } catch (_: Exception) {}
                // M2.5: playback failed but the text reply stands — keep
                // the conversational rhythm anyway.
                finishUtterance(enterConversation = true)
                true
            }
            mp.prepare()
            // SPEAKING is real: playback actually started.
            markPlayed(messageId)
            audioStatus = "playing"
            if (turnGeneration == conversationGeneration) {
                activeTurn?.playbackStartedAtElapsed = SystemClock.elapsedRealtime()
            }
            setState(VoiceState.SPEAKING)
            logEvent("AUDIO SPEAKING — id=$messageId playing ${bytes.size} bytes MP3")
            mp.start()
        } catch (e: Exception) {
            logEvent("AUDIO FAILED — id=$messageId ${e.javaClass.simpleName}: ${e.message}")
            audioStatus = "audio error"
            releasePlayer()
            // M2.5: text reply stands — keep the conversational rhythm.
            finishUtterance(enterConversation = true)
        }
    }

    private fun releasePlayer() {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }
    private fun armFailsafe() {
        cancelFailsafe()
        // V1: capture the generation — a stale failsafe must never cancel a
        // newer turn's recognizer.
        val failsafeGeneration = conversationGeneration
        val r = Runnable {
            if (failsafeGeneration != conversationGeneration) {
                logEvent("STT FAILSAFE — stale (gen=$failsafeGeneration, " +
                    "current=$conversationGeneration), ignoring")
                return@Runnable
            }
            if (currentState.get() == VoiceState.CAPTURING ||
                currentState.get() == VoiceState.TRANSCRIBING) {
                logEvent("STT FAILSAFE — no result/error within " +
                    "${Config.STT_FAILSAFE_TIMEOUT_MS}ms, cancelling recognizer")
                utterance?.error = "failsafe timeout"
                latestTranscriptError = "failsafe timeout"
                try { speechRecognizer?.cancel() } catch (_: Exception) {}
                finishUtterance()
            }
        }
        failsafeRunnable = r
        mainHandler.postDelayed(r, Config.STT_FAILSAFE_TIMEOUT_MS)
    }

    private fun cancelFailsafe() {
        failsafeRunnable?.let { mainHandler.removeCallbacks(it) }
        failsafeRunnable = null
    }

    private fun cancelConversationTimer() {
        conversationTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        conversationTimeoutRunnable = null
    }

    /**
     * V1: close the follow-up window without disturbing anything else.
     * Cancels the recognizer, clears the deadline, and returns to standby.
     * Never advances the generation — closing a window is not a new turn.
     */
    private fun closeFollowUpWindow() {
        cancelConversationTimer()
        conversationDeadlineElapsed = 0L
        conversationEndsAtMs = 0L
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        logEvent("CONVERSATION — follow-up window closed, returning to standby")
        finishUtterance(enterConversation = false)
    }

    // ------------------------------------------------------------------
    // M2.5: conversation mode — natural turn-taking.
    //
    // After a reply finishes (TTS completed, or text-only fallback), the
    // app enters CONVERSATION_PAUSE instead of going straight back to
    // WAKE_IDLE: the mic stays warm for 12s and a follow-up needs NO wake
    // word. openWakeWord stays paused the whole time (it was paused at
    // CAPTURING and is never resumed until the conversation ends), so
    // Vanessa can't self-trigger and the wake word can't double-fire.
    // Silence for the full window → finishUtterance(false) → WAKE_IDLE.
    // ------------------------------------------------------------------

    /**
     * Open the post-reply conversation window. The wake pipeline stays
     * paused; only SpeechRecognizer listens, for up to 12s.
     */
    private fun beginConversationPause() {
        val det = detector
        if (det == null || !running) {
            logEvent("CONVERSATION — detector gone or stopping; skipping pause")
            finishUtterance(enterConversation = false)
            return
        }
        setState(VoiceState.CONVERSATION_PAUSE)
        // V1: the follow-up window is a MONOTONIC deadline, independent of
        // VoiceState. onEndOfSpeech()/onError() can flip states around, but
        // only this deadline decides whether the window is still open.
        val nowElapsed = SystemClock.elapsedRealtime()
        conversationDeadlineElapsed = nowElapsed + Config.CONVERSATION_WINDOW_MS
        conversationEndsAtMs =
            System.currentTimeMillis() + Config.CONVERSATION_WINDOW_MS
        conversationRestarts = 0
        logEvent("CONVERSATION — listening for follow-up for " +
            "${Config.CONVERSATION_WINDOW_MS / 1000}s (no wake word needed)")
        // Fresh instrumentation session for the possible follow-up turn.
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        utterance = UtteranceSession(wakeTimestamp = ts, wakeScore = -1f).also {
            it.captureStartElapsed = System.currentTimeMillis()
        }
        lastPartialText = ""
        cancelConversationTimer()
        // V1: capture this window's generation — a stale timer must never
        // close a NEWER turn's follow-up window.
        val windowGeneration = conversationGeneration
        val timeout = Runnable {
            if (windowGeneration != conversationGeneration) {
                logEvent("CONVERSATION — stale timeout (gen=$windowGeneration, " +
                    "current=$conversationGeneration), ignoring")
                return@Runnable
            }
            if (isFollowUpWindowOpen()) {
                logEvent("CONVERSATION — no follow-up, returning to standby")
                closeFollowUpWindow()
            }
        }
        conversationTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, Config.CONVERSATION_WINDOW_MS)
        startSpeechRecognition()
    }

    /**
     * End of utterance (result, error, or timeout): log the session summary,
     * then either open the conversation window (enterConversation=true) or
     * resume the wake pipeline and return to verified WAKE_IDLE.
     */
    private fun finishUtterance(enterConversation: Boolean = false) {
        cancelFailsafe()
        cancelConversationTimer()
        val u = utterance
        if (u != null) {
            logEvent("Utterance session: ${u.summary()}")
        }
        utterance = null

        // M2.5: a stray finishUtterance(false) must never interrupt an
        // active conversation window (e.g. a duplicate delivery path).
        if (!enterConversation &&
            currentState.get() == VoiceState.CONVERSATION_PAUSE) {
            logEvent("finishUtterance — conversation window active, not interrupting")
            return
        }

        // M2.5: conversation mode — keep the wake pipeline paused and
        // listen for a follow-up instead of resuming it.
        if (enterConversation) {
            beginConversationPause()
            return
        }

        val det = detector
        if (det == null || !running) {
            logEvent("finishUtterance — detector gone or stopping; not resuming")
            if (running) setState(VoiceState.WAKE_IDLE) else setState(VoiceState.OFF)
            return
        }

        logEvent("Resuming wake pipeline (models still loaded)")
        setState(VoiceState.STARTING)
        val ok = det.resumeAudio()
        if (!ok) {
            logEvent("FAILED to resume wake audio — entering PAUSED_ERROR")
            setState(VoiceState.PAUSED_ERROR)
            cleanup()
            return
        }
        det.setDetectionEnabled(true)
        // WAKE_IDLE only after fresh frames prove the pipeline is alive again.
        verifyWakeIdleAfterResume()
    }

    // ------------------------------------------------------------------
    // Lifecycle / cleanup
    // ------------------------------------------------------------------

    private fun cleanup() {
        logEvent("cleanup: releasing openWakeWord detector + microphone")
        cancelFailsafe()
        cancelConversationTimer()
        stopRelayWs()
        releasePlayer()
        releaseWakeLock()
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        try { detector?.stop() } catch (_: Exception) {}
        try { detector?.release() } catch (_: Exception) {}
        detector = null
        serviceRunning = false

        if (currentState.get() != VoiceState.PAUSED_ERROR) {
            setState(VoiceState.OFF)
        }
        logEvent("State: ${currentState.get()} — microphone released")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        serviceRunning = false
        cancelFailsafe()
        cancelConversationTimer()
        stopRelayWs()
        releasePlayer()
        releaseWakeLock()
        if (screenReceiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            screenReceiverRegistered = false
        }
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
        try { detector?.stop() } catch (_: Exception) {}
        try { detector?.release() } catch (_: Exception) {}
        detector = null
        super.onDestroy()
    }

    /**
     * Single serialized state controller. All transitions funnel through here.
     * Must be called on the main thread (all current callers are main-thread:
     * service lifecycle, detector callback via main handler, recognizer
     * callbacks). Guarded anyway for safety.
     */
    @Synchronized
    private fun setState(s: VoiceState) {
        val prev = currentState.get()
        if (prev != s) {
            currentState.set(s)
            Log.d(TAG, "State: $prev → $s")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Natalie Listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while Natalie is listening for the wake word"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ListeningForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // M2.4: the persistent notification makes hands-free state visible
        // at a glance, with a Stop action to end listening.
        val title = if (handsFreeEnabled)
            "Natalie is listening (hands-free ON)"
        else
            "Natalie is listening for '${Config.TEST_KEYWORD_LABEL}'"
        val text = if (handsFreeEnabled)
            "Say 'Hi Natalie' anytime — tap Stop to turn off"
        else
            "Tap Stop to turn off the microphone"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }
}
