package com.natalie.assistant

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * M2.2: minimal HTTP client for the Natalie Cloudflare relay.
 *
 * Contract (from ~/workspace/natalie-relay/src/index.ts):
 * - POST /v1/messages {"id": uuid, "body": text} → 201 {id, seq, duplicate}
 * - GET  /v1/replies?cursor=N → {replies: [{seq, message_id, body, created_at}], cursor, has_more}
 * - GET  /v1/events?cursor=N  → {events: [...], cursor} (status events, optional)
 *
 * Auth: Authorization: Bearer <PHONE_TOKEN>, x-conversation-id: main.
 * All calls are synchronous — the caller MUST run them off the main thread.
 */
object RelayClient {

    private const val BASE = "https://natalie-relay.kareemtv786.workers.dev"
    private const val CONVERSATION_ID = "main"
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 30000

    data class PostResult(val httpCode: Int, val seq: Long, val duplicate: Boolean, val error: String?)
    data class Reply(val seq: Long, val messageId: String, val body: String, val createdAt: Long)
    data class RepliesResult(val httpCode: Int, val replies: List<Reply>, val cursor: Long, val hasMore: Boolean, val error: String?)
    data class AudioResult(val httpCode: Int, val audioBase64: String?, val error: String?)

    private fun openConnection(path: String): HttpURLConnection {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Authorization", "Bearer ${RelaySecrets.PHONE_TOKEN}")
        conn.setRequestProperty("x-conversation-id", CONVERSATION_ID)
        conn.setRequestProperty("Content-Type", "application/json")
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    /** POST a transcript as a new message. Returns HTTP code + server seq. */
    fun postMessage(id: String, body: String): PostResult {
        return try {
            val conn = openConnection("/v1/messages")
            conn.requestMethod = "POST"
            conn.doOutput = true
            val payload = JSONObject().put("id", id).put("body", body).toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            val code = conn.responseCode
            val resp = readBody(conn)
            conn.disconnect()
            if (code == 200 || code == 201) {
                val json = JSONObject(resp)
                PostResult(code, json.optLong("seq", -1), json.optBoolean("duplicate", false), null)
            } else {
                PostResult(code, -1, false, "HTTP $code: ${resp.take(200)}")
            }
        } catch (e: Exception) {
            PostResult(-1, -1, false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** GET replies newer than cursor. */
    fun pollReplies(cursor: Long): RepliesResult {        return try {
            val conn = openConnection("/v1/replies?cursor=$cursor")
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val resp = readBody(conn)
            conn.disconnect()
            if (code == 200) {
                val json = JSONObject(resp)
                val arr: JSONArray = json.optJSONArray("replies") ?: JSONArray()
                val replies = mutableListOf<Reply>()
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    replies.add(
                        Reply(
                            seq = r.getLong("seq"),
                            messageId = r.getString("message_id"),
                            body = r.getString("body"),
                            createdAt = r.getLong("created_at")
                        )
                    )
                }
                RepliesResult(code, replies, json.optLong("cursor", cursor), json.optBoolean("has_more", false), null)
            } else {
                RepliesResult(code, emptyList(), cursor, false, "HTTP $code: ${resp.take(200)}")
            }
        } catch (e: Exception) {
            RepliesResult(-1, emptyList(), cursor, false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * POST an early wake hint. Fires the moment "Hi Natalie" is detected,
     * BEFORE speech capture/transcription completes. Lets the server-side
     * agent warm up during the ~5-15s of capture + STT. This is a HINT,
     * not a command — the server must never generate a reply from it alone.
     * Rate-limited by the caller (max 1 per 30s).
     */
    data class WakeHintResult(val httpCode: Int, val ok: Boolean, val error: String?)

    fun sendWakeHint(): WakeHintResult {
        return try {
            val conn = openConnection("/v1/wake_hint")
            conn.requestMethod = "POST"
            conn.doOutput = true
            val payload = JSONObject()
                .put("wake_at", System.currentTimeMillis())
                .toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            val code = conn.responseCode
            val resp = readBody(conn)
            conn.disconnect()
            if (code == 200 || code == 201) {
                WakeHintResult(code, true, null)
            } else {
                WakeHintResult(code, false, "HTTP $code: ${resp.take(200)}")
            }
        } catch (e: Exception) {
            WakeHintResult(-1, false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * GET TTS audio for a reply. Phone role only.
     * Returns audio_base64 (MP3) when the server has generated it,
     * or httpCode=404 when TTS isn't ready yet (caller should poll).
     */
    fun fetchReplyAudio(messageId: String): AudioResult {
        return try {
            val conn = openConnection("/v1/replies/$messageId/audio")
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val resp = readBody(conn)
            conn.disconnect()
            when (code) {
                200 -> {
                    val json = JSONObject(resp)
                    AudioResult(code, json.optString("audio_base64", null), null)
                }
                404 -> AudioResult(code, null, "no audio yet")
                else -> AudioResult(code, null, "HTTP $code: ${resp.take(200)}")
            }
        } catch (e: Exception) {
            AudioResult(-1, null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
