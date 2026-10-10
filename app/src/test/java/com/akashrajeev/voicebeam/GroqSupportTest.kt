package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GroqSupportTest {
    private val goodKey = "gsk_" + "a".repeat(40)

    @Test fun keyParseAcceptsCleanKeyAndTrims() {
        assertEquals(goodKey, GroqKey.parse(goodKey + "\n"))
    }
    @Test fun keyParseRejectsBadValues() {
        assertNull(GroqKey.parse(null))
        assertNull(GroqKey.parse("short"))
        assertNull(GroqKey.parse("gsk_" + "a".repeat(20) + " " + "b".repeat(20)))
        assertNull(GroqKey.parse("gsk_" + "a".repeat(300)))
        assertNull(GroqKey.parse("gsk_" + "a".repeat(30) + "\r\nX-Evil: 1"))
    }
    @Test fun keyExtractFindsTokenInsideSharedText() {
        assertEquals(goodKey, GroqKey.extract("my key: " + goodKey + " thanks"))
        assertEquals(goodKey, GroqKey.extract(goodKey))
        assertNull(GroqKey.extract("no key here"))
        assertNull(GroqKey.extract(null))
    }
    @Test fun keySaveAndClearRoundTrip() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            assertFalse(GroqKey.save(dir, "not a key"))
            assertNull(GroqKey.load(listOf(dir)))
            assertTrue(GroqKey.save(dir, "  " + goodKey + "\n"))
            assertEquals(goodKey, GroqKey.load(listOf(dir)))
            val other = "gsk_" + "b".repeat(40)
            assertTrue(GroqKey.save(dir, other))
            assertEquals(other, GroqKey.load(listOf(dir)))
            GroqKey.clear(listOf(null, dir))
            assertNull(GroqKey.load(listOf(dir)))
        } finally { dir.deleteRecursively() }
    }
    @Test fun keyLoadReadsPrivateFileOrReturnsNull() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            assertNull(GroqKey.load(listOf(null, dir)))
            File(dir, GroqKey.FILE_NAME).writeText(goodKey + "\n")
            assertEquals(goodKey, GroqKey.load(listOf(null, dir)))
        } finally { dir.deleteRecursively() }
    }
    @Test fun wavHeaderAndSamples() {
        val wav = Pcm16Wav.encode(floatArrayOf(0.5f, Float.NaN, 2f, -2f), 16000)
        assertEquals(44 + 8, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16000, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(8, b.getInt(40))
        assertEquals(16383, b.getShort(44).toInt())
        assertEquals(0, b.getShort(46).toInt())
        assertEquals(32767, b.getShort(48).toInt())
        assertEquals(-32767, b.getShort(50).toInt())
    }
    @Test fun multipartHasFieldsFileAndClosingBoundary() {
        val body = String(Multipart.build("BND", listOf("model" to "whisper-large-v3-turbo", "language" to "en"),
            "file", "utterance.wav", "audio/wav", byteArrayOf(1, 2, 3)), Charsets.ISO_8859_1)
        assertTrue(body.contains("--BND\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-large-v3-turbo\r\n"))
        assertTrue(body.contains("name=\"file\"; filename=\"utterance.wav\"\r\nContent-Type: audio/wav\r\n\r\n"))
        assertTrue(body.endsWith("\r\n--BND--\r\n"))
    }
    @Test fun responseParsesTextAndEscapes() {
        val json = """{"text":" Hi \"there\" \u00e9\nnext","x_groq":{"id":"r"}}"""
        assertEquals(" Hi \"there\" \u00e9\nnext", GroqResponse.parseText(json))
    }
    @Test fun responseErrorBodyIsNull() {
        assertNull(GroqResponse.parseText("""{"error":{"message":"bad","type":"x"}}"""))
        assertNull(GroqResponse.parseText("not json"))
        assertNull(GroqResponse.parseText("""{"text":"unterminated"""))
    }
    @Test fun guardRules() {
        assertNull(GroqGuard.reject("hello world", "Hello world."))
        assertEquals("empty", GroqGuard.reject("hello", " "))
        assertEquals("device_empty", GroqGuard.reject("", "hello"))
        assertEquals("silence_phrase", GroqGuard.reject("ok", "Thank you."))
        assertEquals("runaway", GroqGuard.reject("one two", "x".repeat(200)))
    }
    @Test fun gateCapsRateAndCoolsDown() {
        val g = GroqGate(maxPerMinute = 2, cooldownMs = 1000L)
        assertNull(g.allow(0L)); assertNull(g.allow(1L))
        assertEquals("rate_cap", g.allow(2L))
        assertNull(g.allow(60_001L))
        g.failure(100_000L)
        assertEquals("cooldown", g.allow(100_500L))
        assertNull(g.allow(101_000L))
    }
    @Test fun gateDisableAndRetryAfter() {
        val g = GroqGate(maxPerMinute = 5, cooldownMs = 1000L)
        g.failure(0L, 30_000L)
        assertEquals("cooldown", g.allow(29_000L))
        g.disable()
        assertEquals("disabled", g.allow(500_000L))
    }
    @Test fun errorReasonsNeverCarryMessagesOrKey() {
        assertEquals("timeout", GroqErrors.reason(SocketTimeoutException("Bearer " + goodKey)))
        val r = GroqErrors.reason(RuntimeException(goodKey))
        assertEquals("error_RuntimeException", r)
        assertFalse(r.contains("gsk_"))
        assertEquals("auth", GroqErrors.httpReason(401))
        assertEquals("rate_limited", GroqErrors.httpReason(429))
        assertEquals("http_500", GroqErrors.httpReason(500))
    }
    @Test fun traceLineNeverContainsKeyEvenIfPassedAsField() {
        val line = CaptionTrace.utterance("groq", "groq", 10L, 20L, 5, GroqErrors.reason(RuntimeException(goodKey)))
        assertFalse(line.contains(goodKey))
    }
}
