package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.cloud.*
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

class CloudCleanTest {
    private class MemStore : UsageStore {
        var v: Pair<String, Int>? = null
        override fun read() = v
        override fun write(month: String, seconds: Int) { v = month to seconds }
    }
    private class FakeCleaner(val result: CleanResult) : AudioCleaner {
        override val id = "fake"; override val sendsAudioOffDevice = true
        var calls = 0
        override fun clean(pcm: ByteArray): CleanResult { calls++; return result }
    }
    private fun pcm(seconds: Int) = ByteArray(seconds * 32_000)
    private fun ok(sec: Int) = CleanResult.Cleaned(byteArrayOf(1, 2, 3), "mp3", sec)

    @Test fun offByDefaultNeverCallsCleaner() {
        val c = FakeCleaner(ok(5)); val s = CloudCleanService(c, UsageLedger(MemStore()) { "2026-10" }) { true }
        val r = s.run(false, 8, pcm(5))
        assertEquals(FallbackReason.DISABLED, (r as CleanResult.Fallback).reason); assertEquals(0, c.calls)
    }
    @Test fun noKeyFallsBack() {
        val c = FakeCleaner(ok(5)); val s = CloudCleanService(c, UsageLedger(MemStore()) { "2026-10" }) { false }
        assertEquals(FallbackReason.NO_KEY, (s.run(true, 8, pcm(5)) as CleanResult.Fallback).reason); assertEquals(0, c.calls)
    }
    @Test fun successCountsSecondsAndCapStopsLater() {
        val store = MemStore(); val led = UsageLedger(store) { "2026-10" }
        val c = FakeCleaner(ok(60)); val s = CloudCleanService(c, led) { true }
        assertTrue(s.run(true, 2, pcm(60)) is CleanResult.Cleaned)
        assertEquals(60, led.usedSeconds())
        assertTrue(s.run(true, 2, pcm(60)) is CleanResult.Cleaned)
        val r = s.run(true, 2, pcm(1))
        assertEquals(FallbackReason.OVER_CAP, (r as CleanResult.Fallback).reason); assertEquals(2, c.calls)
    }
    @Test fun clipLargerThanRemainingIsRefusedBeforeUpload() {
        val c = FakeCleaner(ok(30)); val s = CloudCleanService(c, UsageLedger(MemStore()) { "m" }) { true }
        assertEquals(FallbackReason.OVER_CAP, (s.run(true, 1, pcm(61)) as CleanResult.Fallback).reason); assertEquals(0, c.calls)
    }
    @Test fun newMonthResetsUsage() {
        var month = "2026-10"; val store = MemStore(); val led = UsageLedger(store) { month }
        led.record(300); assertEquals(300, led.usedSeconds())
        month = "2026-11"; assertEquals(0, led.usedSeconds()); assertEquals(480, led.remainingSeconds(8))
    }
    @Test fun failureDoesNotCountAgainstCap() {
        val led = UsageLedger(MemStore()) { "m" }
        val s = CloudCleanService(FakeCleaner(CleanResult.Fallback(FallbackReason.OFFLINE)), led) { true }
        assertTrue(s.run(true, 8, pcm(10)) is CleanResult.Fallback); assertEquals(0, led.usedSeconds())
    }
    @Test fun tooShortAndTooLong() {
        val s = CloudCleanService(FakeCleaner(ok(1)), UsageLedger(MemStore()) { "m" }) { true }
        assertEquals(FallbackReason.TOO_SHORT, (s.run(true, 8, ByteArray(100)) as CleanResult.Fallback).reason)
        assertEquals(FallbackReason.TOO_LONG, (s.run(true, 999, pcm(301)) as CleanResult.Fallback).reason)
    }

    @Test fun elevenLabsRequestShapeAndKeyOnlyInHeader() {
        var url = ""; var hdr = emptyMap<String, String>(); var ct = ""; var body = ByteArray(0)
        val cl = ElevenLabsCleaner({ " secretkey123 " }, HttpTransport { u, h, c, b, _ -> url = u; hdr = h; ct = c; body = b; HttpResult(200, byteArrayOf(9)) }, boundary = "BND")
        val r = cl.clean(pcm(2))
        assertTrue(r is CleanResult.Cleaned); assertEquals(2, (r as CleanResult.Cleaned).billedSeconds); assertEquals("mp3", r.extension)
        assertEquals("https://api.elevenlabs.io/v1/audio-isolation", url)
        assertEquals("secretkey123", hdr["xi-api-key"]); assertEquals("multipart/form-data; boundary=BND", ct)
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"file_format\"\r\n\r\npcm_s16le_16")); assertTrue(text.contains("name=\"audio\"; filename=\"clip.pcm\""))
        assertFalse(text.contains("secretkey123")); assertTrue(text.endsWith("--BND--\r\n"))
        assertTrue(body.size > 64_000)
    }
    private fun codeCleaner(code: Int) = ElevenLabsCleaner({ "k" }, { _, _, _, _, _ -> HttpResult(code, byteArrayOf(1)) })
    @Test fun statusMapping() {
        fun reason(code: Int) = (codeCleaner(code).clean(pcm(1)) as CleanResult.Fallback).reason
        assertEquals(FallbackReason.KEY_REJECTED, reason(401)); assertEquals(FallbackReason.KEY_REJECTED, reason(403))
        assertEquals(FallbackReason.QUOTA_EXHAUSTED, reason(402)); assertEquals(FallbackReason.QUOTA_EXHAUSTED, reason(429))
        assertEquals(FallbackReason.SERVER_ERROR, reason(500)); assertEquals(FallbackReason.SERVER_ERROR, reason(302))
    }
    @Test fun offlineIsSoftFail() {
        val cl = ElevenLabsCleaner({ "k" }, { _, _, _, _, _ -> throw UnknownHostException("x") })
        assertEquals(FallbackReason.OFFLINE, (cl.clean(pcm(1)) as CleanResult.Fallback).reason)
    }
    @Test fun missingKeyAndNonHttpsRefuse() {
        var called = false; val t = HttpTransport { _, _, _, _, _ -> called = true; HttpResult(200, byteArrayOf(1)) }
        assertEquals(FallbackReason.NO_KEY, (ElevenLabsCleaner({ null }, t).clean(pcm(1)) as CleanResult.Fallback).reason)
        assertEquals(FallbackReason.NO_KEY, (ElevenLabsCleaner({ "  " }, t).clean(pcm(1)) as CleanResult.Fallback).reason)
        assertEquals(FallbackReason.SERVER_ERROR, (ElevenLabsCleaner({ "k" }, t, "http://x").clean(pcm(1)) as CleanResult.Fallback).reason)
        assertFalse(called)
    }
    @Test fun passthroughNeverSends() {
        val p = PassthroughCleaner(); assertFalse(p.sendsAudioOffDevice); assertTrue(p.clean(ByteArray(1)) is CleanResult.Fallback)
    }
    @Test fun pcmConversionAndResample() {
        val b = Pcm16k.fromFloats(floatArrayOf(0f, 1f, -1f, 2f), 16_000)
        assertEquals(8, b.size); assertEquals(0x7fff, ((b[3].toInt() shl 8) or (b[2].toInt() and 0xff))); 
        assertEquals(32_000, Pcm16k.fromFloats(FloatArray(48_000), 48_000).size)
    }
    @Test fun keyParsing() {
        assertEquals("sk_0123456789abcdef", ElevenKey.parse("  sk_0123456789abcdef \n"))
        assertNull(ElevenKey.parse(null)); assertNull(ElevenKey.parse("short")); assertNull(ElevenKey.parse("has space inside key 123456"))
    }
}
