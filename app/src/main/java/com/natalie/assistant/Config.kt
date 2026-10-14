package com.natalie.assistant

/**
 * M1 configuration (openWakeWord edition).
 *
 * Wake-word engine: openWakeWord (Apache 2.0) via ONNX Runtime — fully
 * on-device, no network, no API key, no account, no fees.
 *
 * Built-in test keyword for M1: "Hey Jarvis" (pre-trained model bundled
 * with the openwakeword-android library, CC BY-NC-SA 4.0).
 * A custom "Hi Natalie" model can be trained later with the openWakeWord
 * training notebook and loaded via setModelAsset() — no vendor approval needed.
 */
object Config {
    /** Built-in test keyword for M1. Custom "Hi Natalie" ONNX model comes in a later milestone. */
    const val TEST_KEYWORD_LABEL = "Hi Natalie"

    /** Detection threshold 0.01–0.99. Lower = more sensitive. */
    const val DETECTION_THRESHOLD = 0.5f

    /** Minimum ms between detections (debounce). */
    const val DEBOUNCE_MS = 2000L

    /** Wake chime enabled — plays a short local tone on detection. */
    const val WAKE_CHIME_ENABLED = true

    // ------------------------------------------------------------------
    // M2.1: on-device speech transcription (SpeechRecognizer)
    // ------------------------------------------------------------------

    /** Prefer offline (on-device) recognition when the engine supports it. */
    const val STT_PREFER_OFFLINE = true

    /** Silence (ms) after speech before the recognizer finalizes the utterance.
     *  Maps to the M2 plan's 1.2–1.5s energy-VAD silence timeout. */
    const val STT_COMPLETE_SILENCE_MS = 1500

    /** Silence (ms) the recognizer tolerates mid-utterance before giving up. */
    const val STT_POSSIBLY_COMPLETE_SILENCE_MS = 1500

    /** Minimum utterance length (ms) the recognizer will accept. */
    const val STT_MINIMUM_LENGTH_MS = 2000

    /** Failsafe: if no result/error arrives within this long, cancel and recover. */
    const val STT_FAILSAFE_TIMEOUT_MS = 20000L

    // ------------------------------------------------------------------
    // M2.2: Cloudflare relay (transcript → Natalie text reply)
    // ------------------------------------------------------------------

    /** Poll interval while waiting for Natalie's reply. */
    const val RELAY_POLL_INTERVAL_MS = 2000L

    /**
     * Max time to wait for a reply before giving up. Muse activation has
     * previously taken tens of seconds — no arbitrary short timeout.
     */
    const val RELAY_REPLY_TIMEOUT_MS = 120000L

    /** SharedPreferences file for relay cursors. */
    const val RELAY_PREFS = "natalie_relay"

    /** Key for the persisted reply cursor (high-watermark seq). */
    const val RELAY_CURSOR_KEY = "reply_cursor"

    /**
     * Key for the persisted set of sent-but-unreplied message IDs.
     * Entries are "uuid:sendCursor" so startup recovery can re-poll
     * from the earliest point any pending message was sent.
     */
    const val PENDING_IDS_KEY = "pending_message_ids"

    // ------------------------------------------------------------------
    // M2.3: Vanessa TTS audio playback (full voice loop)
    // ------------------------------------------------------------------

    /** Poll interval while waiting for TTS audio for a reply. */
    const val AUDIO_POLL_INTERVAL_MS = 2000L

    /**
     * Max time to wait for TTS audio after the text reply arrives.
     * The server generates TTS after the text reply — be patient, but
     * don't leave the user hanging forever. Falls back to text.
     */
    const val AUDIO_TIMEOUT_MS = 60000L

    // ------------------------------------------------------------------
    // M2.4: hands-free mode (persistent always-on listening)
    // ------------------------------------------------------------------

    /** SharedPreferences file for UI-level preferences (hands-free toggle). */
    const val UI_PREFS = "natalie_prefs"

    /** Key for the persisted hands-free toggle. */
    const val HANDSFREE_ENABLED_KEY = "handsfree_enabled"

    /** WakeLock tag for hands-free CPU keep-alive during screen-off. */
    const val HANDSFREE_WAKELOCK_TAG = "Natalie::HandsFreeWakeLock"

    // ------------------------------------------------------------------
    // M2.5: WebSocket push + conversation mode + thinking indicator
    // ------------------------------------------------------------------

    /** WebSocket endpoint path on the relay (wss, phone role only). */
    const val WS_PATH = "/v1/ws"

    /** App-level keepalive: client sends {"type":"ping"} this often. */
    const val WS_PING_INTERVAL_MS = 30000L

    /** Reconnect backoff: first retry delay, doubling up to the max. */
    const val WS_RECONNECT_BASE_MS = 1000L
    const val WS_RECONNECT_MAX_MS = 30000L

    /**
     * Conversation window: after TTS finishes, listen for a follow-up
     * (no wake word needed) for this long before returning to standby.
     */
    const val CONVERSATION_WINDOW_MS = 12000L

    /**
     * Thinking indicator: if a reply takes longer than this, the UI
     * acknowledges the wait ("Still thinking...") instead of the plain
     * "Natalie is thinking..." beat.
     */
    const val THINKING_LONG_MS = 8000L
}
