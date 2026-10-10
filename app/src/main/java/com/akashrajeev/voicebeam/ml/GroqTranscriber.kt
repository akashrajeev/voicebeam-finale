package com.akashrajeev.voicebeam.ml

import com.akashrajeev.voicebeam.core.GroqErrors
import com.akashrajeev.voicebeam.core.GroqGate
import com.akashrajeev.voicebeam.core.GroqResponse
import com.akashrajeev.voicebeam.core.Multipart
import com.akashrajeev.voicebeam.core.Pcm16Wav
import java.net.HttpURLConnection
import java.net.URL

/** Optional online caption path. Moonshine on the phone stays the fallback for every failure. */
class GroqTranscriber(private val key: String, private val gate: GroqGate = GroqGate()) {
    class Result(val text: String?, val reason: String?, val ms: Long)

    fun transcribe(samples: FloatArray, rate: Int): Result {
        val start = android.os.SystemClock.elapsedRealtime()
        fun done(text: String?, reason: String?) = Result(text, reason, android.os.SystemClock.elapsedRealtime() - start)
        val blocked = gate.allow(start)
        if (blocked != null) return done(null, blocked)
        var conn: HttpURLConnection? = null
        try {
            val boundary = "vb" + java.lang.Long.toHexString(System.nanoTime())
            val body = Multipart.build(boundary,
                listOf("model" to "whisper-large-v3-turbo", "language" to "en", "response_format" to "json", "temperature" to "0"),
                "file", "utterance.wav", "audio/wav", Pcm16Wav.encode(samples, rate))
            conn = URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 1500
            conn.readTimeout = 2500
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer " + key)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) {
                if (code == 401 || code == 403) gate.disable()
                val retry = (conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 0L) * 1000L
                gate.failure(android.os.SystemClock.elapsedRealtime(), retry)
                return done(null, GroqErrors.httpReason(code))
            }
            val text = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            val parsed = GroqResponse.parseText(text) ?: return done(null, "bad_response")
            return done(parsed, null)
        } catch (t: Throwable) {
            gate.failure(android.os.SystemClock.elapsedRealtime())
            return done(null, GroqErrors.reason(t))
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
