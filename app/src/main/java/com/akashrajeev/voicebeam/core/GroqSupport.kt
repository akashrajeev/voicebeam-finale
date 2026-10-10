package com.akashrajeev.voicebeam.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the optional demo key from a private file on the phone. Never from the APK or repo. */
object GroqKey {
    const val FILE_NAME = "groq.key"
    /** Returns a clean key or null. Rejects whitespace, odd characters and absurd lengths. */
    fun parse(raw: String?): String? {
        val k = raw?.trim() ?: return null
        if (k.length < 20 || k.length > 200) return null
        if (!k.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
        return k
    }
    private val tokenPattern = Regex("gsk_[A-Za-z0-9_-]{20,190}")
    /** Key from shared text: the first gsk_ token inside it, or the whole text when it is just a key. */
    fun extract(text: String?): String? {
        if (text == null) return null
        val m = tokenPattern.find(text)
        return if (m != null) parse(m.value) else parse(text)
    }
    fun load(dirs: List<File?>): String? {
        for (d in dirs) {
            if (d == null) continue
            val f = File(d, FILE_NAME)
            try { if (f.isFile && f.length() in 1L..512L) parse(f.readText())?.let { return it } } catch (_: Throwable) {}
        }
        return null
    }
}

/** 16-bit mono PCM WAV bytes for an upload. */
object Pcm16Wav {
    fun encode(samples: FloatArray, rate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)); b.putInt(36 + dataBytes)
        b.put("WAVE".toByteArray(Charsets.US_ASCII)); b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)); b.putInt(dataBytes)
        for (x in samples) {
            val v = if (x.isFinite()) x.coerceIn(-1f, 1f) else 0f
            b.putShort((v * 32767f).toInt().toShort())
        }
        return b.array()
    }
}

object Multipart {
    fun build(boundary: String, fields: List<Pair<String, String>>, fileField: String, fileName: String,
              fileType: String, file: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) {
            w("--" + boundary + "\r\n"); w("Content-Disposition: form-data; name=\"" + k + "\"\r\n\r\n"); w(v); w("\r\n")
        }
        w("--" + boundary + "\r\n")
        w("Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + fileName + "\"\r\n")
        w("Content-Type: " + fileType + "\r\n\r\n")
        out.write(file); w("\r\n--" + boundary + "--\r\n")
        return out.toByteArray()
    }
}

object GroqResponse {
    /** Value of the top-level "text" string in {"text":"..."}; null for errors or odd bodies. */
    fun parseText(body: String): String? {
        val marker = body.indexOf("\"text\"")
        if (marker < 0) return null
        var i = marker + 6
        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length || body[i] != ':') return null
        i++
        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length || body[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < body.length) {
            val c = body[i]
            if (c == '"') return sb.toString()
            if (c == '\\') {
                i++
                if (i >= body.length) return null
                when (val e = body[i]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'u' -> {
                        if (i + 4 >= body.length) return null
                        val code = body.substring(i + 1, i + 5).toIntOrNull(16) ?: return null
                        sb.append(code.toChar()); i += 4
                    }
                    else -> sb.append(e)
                }
            } else sb.append(c)
            i++
        }
        return null
    }
}

/** Rejects cloud text that looks invented. Returns a reason code, or null when it is fine to use. */
object GroqGuard {
    private val silencePhrases = setOf("thank you", "thanks for watching", "you", "bye", "thank you for watching")
    fun reject(deviceText: String, cloudText: String): String? {
        val c = cloudText.trim()
        if (c.isEmpty()) return "empty"
        val d = deviceText.trim()
        if (d.isEmpty()) return "device_empty"
        val norm = c.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()
        if (norm in silencePhrases && d.split(Regex("\\s+")).size <= 2) return "silence_phrase"
        if (c.length > d.length * 4 + 60) return "runaway"
        return null
    }
}

/** Caps request rate and backs off after failures so the demo never hammers the free tier. */
class GroqGate(private val maxPerMinute: Int = 15, private val cooldownMs: Long = 20_000L) {
    private val sent = ArrayDeque<Long>()
    private var blockedUntil = 0L
    private var disabled = false
    @Synchronized fun allow(nowMs: Long): String? {
        if (disabled) return "disabled"
        if (nowMs < blockedUntil) return "cooldown"
        while (sent.isNotEmpty() && nowMs - sent.first() >= 60_000L) sent.removeFirst()
        if (sent.size >= maxPerMinute) return "rate_cap"
        sent.addLast(nowMs)
        return null
    }
    @Synchronized fun failure(nowMs: Long, retryAfterMs: Long = 0L) { blockedUntil = nowMs + maxOf(cooldownMs, retryAfterMs) }
    @Synchronized fun disable() { disabled = true }
}

object GroqErrors {
    /** Short code for diagnostics. Uses the exception type only, never its message. */
    fun reason(t: Throwable): String = when (t) {
        is SocketTimeoutException -> "timeout"
        is UnknownHostException -> "no_network"
        else -> "error_" + t.javaClass.simpleName.filter { it.isLetterOrDigit() }.take(32)
    }
    fun httpReason(code: Int): String = when (code) {
        401, 403 -> "auth"
        429 -> "rate_limited"
        else -> "http_" + code
    }
}
