package com.akashrajeev.voicebeam.cloud

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpResult(val code: Int, val body: ByteArray)

fun interface HttpTransport {
    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, contentType: String, body: ByteArray, timeoutMs: Int): HttpResult
}

class UrlConnectionTransport : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, contentType: String, body: ByteArray, timeoutMs: Int): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false   // never forward the key to another host
            c.setRequestProperty("Content-Type", contentType)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val out = ByteArrayOutputStream()
            stream?.use { it.copyTo(out) }
            return HttpResult(code, out.toByteArray())
        } finally { c.disconnect() }
    }
}

object Multipart {
    fun build(boundary: String, audio: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        fun s(t: String) = o.write(t.toByteArray(Charsets.UTF_8))
        s("--$boundary\r\nContent-Disposition: form-data; name=\"file_format\"\r\n\r\npcm_s16le_16\r\n")
        s("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"clip.pcm\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        o.write(audio)
        s("\r\n--$boundary--\r\n")
        return o.toByteArray()
    }
}

/**
 * ElevenLabs Voice Isolator, clip level. Sends the clip to https://api.elevenlabs.io (internet needed).
 * The key comes from [keyProvider] (Android Keystore on the phone), is only sent as a request header,
 * and is never logged or put in a message.
 */
class ElevenLabsCleaner(
    private val keyProvider: () -> String?,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val baseUrl: String = "https://api.elevenlabs.io",
    private val timeoutMs: Int = 60_000,
    private val boundary: String = "vb" + java.lang.Long.toHexString(System.nanoTime()),
) : AudioCleaner {
    override val id = "elevenlabs-isolator"
    override val sendsAudioOffDevice = true

    override fun clean(pcm: ByteArray): CleanResult {
        val key = keyProvider()?.trim().orEmpty()
        if (key.isEmpty()) return CleanResult.Fallback(FallbackReason.NO_KEY)
        if (!baseUrl.startsWith("https://")) return CleanResult.Fallback(FallbackReason.SERVER_ERROR)
        val seconds = billedSeconds(pcm.size)
        return try {
            val r = transport.post(
                "$baseUrl/v1/audio-isolation", mapOf("xi-api-key" to key),
                "multipart/form-data; boundary=$boundary", Multipart.build(boundary, pcm), timeoutMs)
            when {
                r.code in 200..299 && r.body.isNotEmpty() -> CleanResult.Cleaned(r.body, "mp3", seconds)
                r.code == 401 || r.code == 403 -> CleanResult.Fallback(FallbackReason.KEY_REJECTED)
                r.code == 402 || r.code == 429 -> CleanResult.Fallback(FallbackReason.QUOTA_EXHAUSTED)
                else -> CleanResult.Fallback(FallbackReason.SERVER_ERROR)
            }
        } catch (_: IOException) {
            CleanResult.Fallback(FallbackReason.OFFLINE)
        } catch (_: RuntimeException) {
            CleanResult.Fallback(FallbackReason.SERVER_ERROR)
        }
    }

    companion object {
        /** Whole seconds, rounded up, 16 kHz 16-bit mono = 32,000 bytes per second. */
        fun billedSeconds(bytes: Int): Int = ((bytes + 31_999) / 32_000).coerceAtLeast(1)
    }
}
