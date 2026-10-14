package com.natalie.assistant

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * M2.5: WebSocket push transport for relay replies.
 *
 * The relay broadcasts on /v1/ws (phone role only):
 * - {"type":"hello","event_cursor":N,"reply_cursor":M} on connect
 * - {"type":"reply","message_id":"...","seq":N,"body":"...","created_at":...}
 * - {"type":"audio","message_id":"..."} — TTS ready for download
 * - {"type":"event","message_id":"...","status":"...",...} — status changes
 * - {"type":"pong"} — answer to our app-level {"type":"ping"}
 *
 * This class owns the connection lifecycle: connect, app-level ping
 * keepalive every 30s, and reconnect with exponential backoff
 * (1s → 2s → 4s → ... capped at 30s) while enabled. All listener
 * callbacks fire on OkHttp's internal threads — the caller must marshal
 * to the main thread itself.
 *
 * The HTTP poll loop in ListeningForegroundService stays active as a
 * live fallback: whichever transport delivers a reply first wins, and
 * deliveries are de-duplicated by message ID on the service side.
 * Every delivery is tagged with its transport for the debug status line.
 */
class RelayWebSocket(private val listener: Listener) {

    interface Listener {
        /** Full reply arrived (body included) — handle like a poll match. */
        fun onWsReply(messageId: String, body: String)
        /** TTS audio is ready — wake the audio poll immediately. */
        fun onWsAudioReady(messageId: String)
        /** Status event (claimed/replied/etc.) — log only. */
        fun onWsStatusEvent(json: JSONObject)
        /** Connected; hello carried the server's reply cursor. */
        fun onWsConnected(serverReplyCursor: Long)
        /** Disconnected (a reconnect is scheduled while enabled). */
        fun onWsDisconnected(reason: String)
    }

    companion object {
        private const val TAG = "NatalieWS"
        private const val HOST = "natalie-relay.kareemtv786.workers.dev"
        private const val CONVERSATION_ID = "main"
        // NOTE: the phone token travels as a query param because Android
        // WebSocket handshakes can't easily set custom headers. The token
        // value is NEVER logged.
        private const val CONNECT_TIMEOUT_S = 15L
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // long-lived stream, no read timeout
        .build()

    private val enabled = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "NatalieWsScheduler").apply { isDaemon = true }
        }
    private var ws: WebSocket? = null
    private var pingTask: ScheduledFuture<*>? = null
    private var reconnectTask: ScheduledFuture<*>? = null

    /** True while the socket is open. */
    fun isConnected(): Boolean = connected.get()

    /** Start connecting (and auto-reconnecting). Safe to call repeatedly. */
    fun start() {
        if (!enabled.compareAndSet(false, true)) return
        reconnectAttempt.set(0)
        connectNow()
    }

    /** Stop everything: cancel reconnect, close socket, stop pings. */
    fun stop() {
        enabled.set(false)
        cancelPing()
        cancelReconnect()
        try {
            ws?.close(1000, "client stopping")
        } catch (_: Exception) { }
        ws = null
        connected.set(false)
    }

    /** Release the scheduler threads (call from Service.onDestroy). */
    fun shutdown() {
        stop()
        try { scheduler.shutdownNow() } catch (_: Exception) { }
    }

    private fun connectNow() {
        if (!enabled.get()) return
        cancelReconnect()
        val url = "wss://$HOST${Config.WS_PATH}" +
            "?token=${RelaySecrets.PHONE_TOKEN}" +
            "&conversation_id=$CONVERSATION_ID"
        val request = Request.Builder().url(url).build()
        // Log the endpoint WITHOUT the token.
        android.util.Log.i(
            TAG,
            "WS connecting to wss://$HOST${Config.WS_PATH} " +
                "(token via query, conversation=$CONVERSATION_ID)"
        )
        try {
            ws = client.newWebSocket(request, socketListener)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "WS newWebSocket threw: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!enabled.get()) return
        cancelReconnect()
        val attempt = reconnectAttempt.getAndIncrement()
        // 1s, 2s, 4s, 8s, 15s, 30s, 30s, ...
        var delayMs = Config.WS_RECONNECT_BASE_MS shl attempt.coerceAtMost(5)
        if (delayMs > Config.WS_RECONNECT_MAX_MS) delayMs = Config.WS_RECONNECT_MAX_MS
        android.util.Log.i(TAG, "WS reconnect attempt #$attempt in ${delayMs}ms")
        reconnectTask = scheduler.schedule(
            { connectNow() }, delayMs, TimeUnit.MILLISECONDS
        )
    }

    private fun cancelReconnect() {
        try { reconnectTask?.cancel(false) } catch (_: Exception) { }
        reconnectTask = null
    }

    private fun startPing() {
        cancelPing()
        pingTask = scheduler.scheduleAtFixedRate(
            {
                try {
                    if (connected.get()) {
                        ws?.send("{\"type\":\"ping\"}")
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "WS ping send failed: ${e.message}")
                }
            },
            Config.WS_PING_INTERVAL_MS,
            Config.WS_PING_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun cancelPing() {
        try { pingTask?.cancel(false) } catch (_: Exception) { }
        pingTask = null
    }

    private fun onSocketDown(reason: String) {
        val was = connected.getAndSet(false)
        cancelPing()
        ws = null
        if (was) {
            android.util.Log.i(TAG, "WS disconnected: $reason")
            try { listener.onWsDisconnected(reason) } catch (_: Exception) { }
        }
        scheduleReconnect()
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt.set(0)
            connected.set(true)
            startPing()
            android.util.Log.i(TAG, "WS open (HTTP ${response.code}) — waiting for hello")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "hello" -> {
                        val rc = json.optLong("reply_cursor", -1L)
                        android.util.Log.i(
                            TAG,
                            "WS hello — event_cursor=${json.optLong("event_cursor", -1L)} " +
                                "reply_cursor=$rc"
                        )
                        try { listener.onWsConnected(rc) } catch (_: Exception) { }
                    }
                    "reply" -> {
                        val id = json.optString("message_id", "")
                        val body = json.optString("body", "")
                        if (id.isNotEmpty()) {
                            android.util.Log.i(
                                TAG,
                                "WS REPLY id=$id seq=${json.optLong("seq", -1L)} " +
                                    "chars=${body.length}"
                            )
                            try { listener.onWsReply(id, body) } catch (_: Exception) { }
                        }
                    }
                    "audio" -> {
                        val id = json.optString("message_id", "")
                        if (id.isNotEmpty()) {
                            android.util.Log.i(TAG, "WS AUDIO-READY id=$id")
                            try { listener.onWsAudioReady(id) } catch (_: Exception) { }
                        }
                    }
                    "event" -> {
                        try { listener.onWsStatusEvent(json) } catch (_: Exception) { }
                    }
                    "pong" -> {
                        android.util.Log.d(TAG, "WS pong")
                    }
                    else -> {
                        android.util.Log.d(TAG, "WS unknown message type: ${text.take(120)}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "WS message parse failed: ${e.message}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            android.util.Log.d(TAG, "WS binary message (${bytes.size} bytes) — ignored")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            android.util.Log.i(TAG, "WS closing: code=$code reason=$reason")
            try { webSocket.close(1000, null) } catch (_: Exception) { }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onSocketDown("closed code=$code reason=$reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val detail = "${t.javaClass.simpleName}: ${t.message} " +
                (response?.let { "(HTTP ${it.code})" } ?: "")
            android.util.Log.w(TAG, "WS failure: $detail")
            onSocketDown("failure: $detail")
        }
    }
}
