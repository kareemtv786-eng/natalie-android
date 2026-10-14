package com.natalie.assistant

/**
 * M2.2 state machine — single authority for microphone/voice state.
 * Only ListeningForegroundService may transition states, and only on the
 * main thread (SpeechRecognizer callbacks + detector callbacks all arrive
 * on the main thread; the audio thread never transitions states).
 *
 * M2.2 adds the relay leg: TRANSCRIBING → SENDING → WAITING_REPLY →
 * REPLY_RECEIVED → WAKE_IDLE. Network I/O runs on a background thread;
 * state transitions are marshalled back to the main thread.
 */
enum class VoiceState {
    OFF,
    STARTING,
    WAKE_IDLE,
    WAKE_DETECTED,
    /** "Hi Natalie" heard — mic handed to SpeechRecognizer, user speaking. */
    CAPTURING,
    /** Speech ended — processing results, about to resume wake listening. */
    TRANSCRIBING,
    /** Transcript confirmed — POSTing to the Cloudflare relay (background thread). */
    SENDING,
    /** Message accepted — polling the relay for Natalie's reply (background thread). */
    WAITING_REPLY,
    /** Natalie's text reply received and displayed. */
    REPLY_RECEIVED,
    /** Vanessa TTS audio is playing — wake detection fully paused until done. */
    SPEAKING,
    /**
     * M2.5: conversation mode — TTS just finished (or text reply stands).
     * The mic stays warm for a follow-up for 12s WITHOUT requiring
     * "Hi Natalie". openWakeWord stays paused (no self-triggering); only
     * SpeechRecognizer is active. Silence for the full window → WAKE_IDLE.
     */
    CONVERSATION_PAUSE,
    PAUSED_ERROR
}
