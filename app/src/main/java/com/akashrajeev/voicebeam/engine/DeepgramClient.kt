package com.akashrajeev.voicebeam.engine

import android.os.SystemClock
import com.akashrajeev.voicebeam.core.DeepgramTranscript
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Deepgram live captions over one WebSocket. Never blocks the caller: audio goes into the socket's
 * own bounded queue and is dropped when the network is slow. Any failure just turns [send] false,
 * and the engine falls back to on-device captions. The key is only sent as the auth header.
 */
class DeepgramClient(private val apiKey: String, val transcript: DeepgramTranscript) {
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile var authFailed = false
        private set
    @Volatile var status = "connecting"
        private set
    private var nextTryMs = 0L
    private var failures = 0

    private fun connect() {
        val now = SystemClock.elapsedRealtime()
        if (authFailed || now < nextTryMs) return
        nextTryMs = now + minOf(30_000L, 2_000L shl minOf(failures, 4))
        val url = "https://api.deepgram.com/v1/listen?model=nova-3&language=en&encoding=linear16&sample_rate=16000&channels=1" +
            "&interim_results=true&smart_format=true&endpointing=400&utterance_end_ms=1000"
        val req = Request.Builder().url(url).header("Authorization", "Token $apiKey").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true; failures = 0; status = "on"; Diagnostics.event("deepgram_open")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val j = JSONObject(text)
                    when (j.optString("type")) {
                        "Results" -> {
                            val alt = j.getJSONObject("channel").getJSONArray("alternatives").optJSONObject(0)
                            transcript.onResult(alt?.optString("transcript") ?: "", j.optBoolean("is_final"), j.optBoolean("speech_final"))
                        }
                        "UtteranceEnd" -> transcript.onUtteranceEnd()
                    }
                } catch (_: Throwable) { }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { drop(webSocket, "closed=$code") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                if (code == 401 || code == 403) { authFailed = true; status = "key rejected" }
                else if (code == 402) { authFailed = true; status = "no credit left" }
                drop(webSocket, "failed=$code ${t.javaClass.simpleName}")
            }
        })
    }

    private fun drop(socket: WebSocket, why: String) {
        if (ws === socket) { ws = null; open = false; failures++; if (!authFailed) status = "offline, using on-device" }
        Diagnostics.event("deepgram_$why")
    }

    /** Queue one 16 kHz mono block. Returns true while the connection is up and carrying audio. */
    fun send(samples: FloatArray): Boolean {
        val s = ws
        if (s == null) { connect(); return false }
        if (!open) return false
        if (s.queueSize() > 96_000L) return true // slow network: drop this block, keep the stream
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            pcm[2 * i] = (v and 0xff).toByte(); pcm[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        s.send(pcm.toByteString())
        return true
    }

    fun close() {
        val s = ws; ws = null; open = false
        runCatching { s?.send("{\"type\":\"CloseStream\"}"); s?.close(1000, null) }
        http.dispatcher.executorService.shutdown()
        transcript.reset()
    }
}
