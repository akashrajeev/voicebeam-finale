package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RoutedRenderTest {
    private val sr = 16000
    private fun tone(sec: Float, hz: Double = 220.0, amp: Float = 0.5f) = FloatArray((sec * sr).toInt()) { (amp * sin(2 * PI * hz * it / sr)).toFloat() }
    private fun segs(n: Int, s: Seg) = Array(n) { s }

    @Test fun targetOnlyWithoutDenoiseIsOriginal() {
        val x = tone(2f); val out = RoutedRender.render(x, null, null, segs(4, Seg.TARGET_ONLY), sr)
        assertArrayEquals(x, out, 1e-6f)
    }
    @Test fun otherOnlyDucksByFocusDb() {
        val x = tone(2f); val out = RoutedRender.render(x, null, null, segs(4, Seg.OTHER_ONLY), sr, focusDb = -18f, xfadeSec = 0f)
        assertEquals(0.1259f, RoutedRender.rms(out) / RoutedRender.rms(x), 0.002f)
    }
    @Test fun defaultFocusIsMinus30() {
        assertEquals(-30f, RoutedRender.DEFAULT_FOCUS_DB, 0f)
        val x = tone(2f); val out = RoutedRender.render(x, null, null, segs(4, Seg.OTHER_ONLY), sr, xfadeSec = 0f)
        assertEquals(0.0316f, RoutedRender.rms(out) / RoutedRender.rms(x), 0.001f)
    }
    @Test fun focusZeroIsNoDuck() {
        val x = tone(2f); assertArrayEquals(x, RoutedRender.render(x, null, null, segs(4, Seg.OTHER_ONLY), sr, focusDb = 0f), 1e-6f)
    }
    @Test fun overlapUsesExtractedWhenPresent() {
        val x = tone(2f); val e = tone(2f, 440.0)
        assertArrayEquals(e, RoutedRender.render(x, null, e, segs(4, Seg.OVERLAP), sr, xfadeSec = 0f), 1e-6f)
    }
    @Test fun overlapWithoutExtractedPassesOriginalNeverFabricates() {
        val x = tone(2f); assertArrayEquals(x, RoutedRender.render(x, null, null, segs(4, Seg.OVERLAP), sr), 1e-6f)
    }
    @Test fun crossfadeHasNoStepAtBoundary() {
        val x = FloatArray(sr * 2) { 0.5f } // DC so any gain step shows up directly
        val labels = arrayOf(Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY)
        val hard = RoutedRender.maxStep(RoutedRender.render(x, null, null, labels, sr, xfadeSec = 0f))
        val soft = RoutedRender.maxStep(RoutedRender.render(x, null, null, labels, sr, xfadeSec = 0.05f))
        assertTrue(hard > 0.3f); assertTrue(soft < 0.01f)
    }
    @Test fun lengthPreservedAndFinite() {
        val x = tone(1.7f); val out = RoutedRender.render(x, x, x, segs(4, Seg.NONE), sr)
        assertEquals(x.size, out.size); assertTrue(out.all { it.isFinite() })
    }
    @Test(expected = IllegalArgumentException::class) fun focusAboveZeroRejected() { RoutedRender.render(tone(1f), null, null, segs(2, Seg.NONE), sr, focusDb = 3f) }
    @Test(expected = IllegalArgumentException::class) fun focusBelowFloorRejected() { RoutedRender.render(tone(1f), null, null, segs(2, Seg.NONE), sr, focusDb = -40f) }
    @Test(expected = IllegalArgumentException::class) fun lengthMismatchRejected() { RoutedRender.render(tone(1f), tone(0.5f), null, segs(2, Seg.NONE), sr) }
    @Test(expected = IllegalArgumentException::class) fun labelsMustCoverAudio() { RoutedRender.render(tone(2f), null, null, segs(1, Seg.NONE), sr) }
    @Test(expected = IllegalArgumentException::class) fun nanInputRejected() {
        val x = tone(1f); x[5] = Float.NaN; RoutedRender.render(x, null, null, segs(2, Seg.NONE), sr)
    }

    // protect()
    private fun voicedFlags(frames: Int, on: IntRange) = BooleanArray(frames) { it in on }

    @Test fun protectSoftFloorKeepsAudibleResidualInsteadOfSilence() {
        val src = tone(2f); val y = tone(2f, 330.0)
        val frames = (src.size + 511) / 512
        val v = voicedFlags(frames, 0..5) // voiced only at the start
        val soft = RoutedRender.protect(y, src, v, floor = 0.1f)
        val hard = RoutedRender.protect(y, src, v, floor = 0f)
        val a = 20 * 512; val b = 30 * 512 // well inside the non-voiced region
        assertTrue(RoutedRender.rms(soft, a, b) > 0.01f)
        assertEquals(0f, RoutedRender.rms(hard, a, b), 1e-4f)
    }
    @Test fun protectNeverExceedsSourceRmsPerFrame() {
        val src = tone(2f, amp = 0.05f); val y = tone(2f, 330.0, amp = 0.9f)
        val frames = (src.size + 511) / 512
        val out = RoutedRender.protect(y, src, BooleanArray(frames) { true })
        for (f in 0 until frames) {
            val a = f * 512; val b = minOf(src.size, a + 512)
            assertTrue(RoutedRender.rms(out, a, b) <= RoutedRender.rms(src, a, b) * 1.001f)
        }
    }
    @Test fun protectHasNoStepDiscontinuity() {
        val src = tone(2f); val y = tone(2f, 330.0)
        val frames = (src.size + 511) / 512
        val out = RoutedRender.protect(y, src, voicedFlags(frames, 0..9), ctxFrames = 0)
        assertTrue(RoutedRender.maxStep(out) < 0.2f)
    }
    @Test fun protectPreservesLength() {
        val src = tone(1.3f); val out = RoutedRender.protect(src, src, BooleanArray(100) { true })
        assertEquals(src.size, out.size)
    }
    @Test fun protectSilentExtractedStaysSilentAndFinite() {
        val src = tone(1f); val out = RoutedRender.protect(FloatArray(src.size), src, BooleanArray(100) { true })
        assertTrue(out.all { it == 0f })
    }
    @Test(expected = IllegalArgumentException::class) fun protectVoicedTooShort() { RoutedRender.protect(tone(2f), tone(2f), BooleanArray(3)) }
    @Test(expected = IllegalArgumentException::class) fun protectLengthMismatch() { RoutedRender.protect(tone(1f), tone(2f), BooleanArray(100)) }
    @Test(expected = IllegalArgumentException::class) fun protectBadFloor() { RoutedRender.protect(tone(1f), tone(1f), BooleanArray(100), floor = 1.5f) }
    @Test(expected = IllegalArgumentException::class) fun protectNanRejected() {
        val y = tone(1f); y[0] = Float.NaN; RoutedRender.protect(y, tone(1f), BooleanArray(100))
    }

    @Test fun extractorWeightIsExactlyZeroInOtherOnlyInteriors() {
        val labels = arrayOf(Seg.OVERLAP, Seg.OTHER_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY, Seg.OVERLAP)
        val xf = 0.05f; val k = (xf * sr / 2f).toInt()
        val n = sr * 5 / 2
        val w = RoutedRender.weights(n, false, true, labels, sr, -18f, xf)
        val binLen = sr / 2
        // interior = at least k+1 samples away from every OVERLAP sample
        for (i in (binLen + k + 1) until (4 * binLen - k - 1)) assertEquals("sample $i", 0f, w.extracted[i], 0f)
        // and the extractor does contribute inside the OVERLAP bins away from edges
        assertEquals(1f, w.extracted[binLen / 2], 1e-6f)
    }
    @Test fun weightsSumToOneEverywhere() {
        val labels = arrayOf(Seg.TARGET_ONLY, Seg.OTHER_ONLY, Seg.OVERLAP, Seg.NONE, Seg.OVERLAP)
        val w = RoutedRender.weights(sr * 5 / 2, true, true, labels, sr, -18f, 0.05f)
        for (i in w.gain.indices) assertEquals(1f, w.original[i] + w.denoised[i] + w.extracted[i], 1e-4f)
    }
    @Test fun protectNonVoicedFrameCarriesNoRampResidue() {
        val x = FloatArray(512 * 12) { 0.3f }
        val voiced = BooleanArray(12) { it < 4 }
        val out = RoutedRender.protect(x, x, voiced, floor = 0.04f, ctxFrames = 0)
        // frame 4 is the first non-voiced frame: it must already sit at the floor from its first sample
        assertEquals(0.3f * 0.04f, out[4 * 512], 1e-4f)
        assertEquals(0.3f * 0.04f, out[5 * 512 - 1], 1e-4f)
        // the ramp happened inside frame 3 and is continuous
        assertTrue(RoutedRender.maxStep(out) < 0.01f)
    }
}
