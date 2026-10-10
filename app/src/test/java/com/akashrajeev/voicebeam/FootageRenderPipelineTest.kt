package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FootageRenderPipelineTest {
    private val sr = 16000
    private val labels = arrayOf(Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY, Seg.OVERLAP, Seg.OVERLAP, Seg.NONE, Seg.NONE) // 4 s
    private val n = sr * 4
    private fun binOf(i: Int) = labels[i / (sr / 2)]
    private fun tone(hz: Double, amp: Float, i: Int) = (amp * sin(2 * PI * hz * i / sr)).toFloat()
    private fun voiced() = BooleanArray((n + 511) / 512) { f -> labels[minOf(labels.size - 1, (f * 512 + 256) / (sr / 2))] != Seg.NONE }

    private val source = FloatArray(n) { i ->
        when (binOf(i)) {
            Seg.TARGET_ONLY -> tone(500.0, 0.3f, i)
            Seg.OTHER_ONLY -> tone(1200.0, 0.3f, i)
            Seg.OVERLAP -> tone(500.0, 0.3f, i) + tone(1200.0, 0.3f, i)
            Seg.NONE -> tone(300.0, 0.002f, i)
        }
    }

    @Test fun honestExtractionRendersAndPassesGates() {
        // realistic extraction: target audio only where the target speaks (TARGET_ONLY / OVERLAP bins), silence elsewhere
        val extracted = FloatArray(n) { if (binOf(it) == Seg.TARGET_ONLY || binOf(it) == Seg.OVERLAP) tone(500.0, 0.28f, it) else 0f }
        val r = FootageRenderPipeline.run(source, extracted, null, labels, voiced())
        assertTrue("expected Rendered, got " + ((r as? RenderOutcome.Rejected)?.gates?.failures), r is RenderOutcome.Rendered)
        val out = (r as RenderOutcome.Rendered).audio
        // other-only bins ducked about -18 dB vs source
        val a = sr; val b = 2 * sr - 1000
        assertTrue(RoutedRender.rms(out, a + 1000, b) < RoutedRender.rms(source, a + 1000, b) * 0.2f)
        // none bins near silent
        assertTrue(RoutedRender.rms(out, 3 * sr + 2000, 4 * sr - 100) < RoutedRender.rms(source, 3 * sr + 2000, 4 * sr - 100) * 0.05f)
    }

    @Test fun fabricatedNarrowbandExtractionIsRejected() {
        // extraction invents a 3 kHz tone where the source has none there
        val extracted = FloatArray(n) { tone(3000.0, 0.5f, it) }
        val r = FootageRenderPipeline.run(source, extracted, null, labels, voiced())
        assertTrue(r is RenderOutcome.Rejected)
        assertTrue((r as RenderOutcome.Rejected).gates.failures.isNotEmpty())
    }

    @Test fun protectHardMutesNoneFramesButSoftFloorsElsewhere() {
        val x = FloatArray(n) { tone(400.0, 0.2f, it) }
        val voiced = BooleanArray((n + 511) / 512) { false }
        val hm = RoutedRender.noneFrames(labels, n, sr)
        val out = RoutedRender.protect(x, x, voiced, floor = 0.1f, hardMute = hm)
        assertTrue(RoutedRender.rms(out, 3 * sr + 4000, 4 * sr - 100) < 1e-3f)     // NONE: hard mute
        assertTrue(RoutedRender.rms(out, 4000, sr - 100) > 0.005f)                    // TARGET_ONLY dip: audible soft floor
    }

    @Test fun noneFramesMatchLabels() {
        val hm = RoutedRender.noneFrames(labels, n, sr)
        assertFalse(hm[10]); assertTrue(hm[hm.size - 3])
    }

    @Test(expected = IllegalArgumentException::class) fun hardMuteMustCoverAudio() {
        RoutedRender.protect(FloatArray(2048), FloatArray(2048), BooleanArray(10), hardMute = BooleanArray(1))
    }

    @Test fun noneMaskIsSampleExactInsideNoneBins() {
        val x = FloatArray(n) { 0.3f }
        val out = RoutedRender.applyNoneMask(x, labels, sr)
        for (i in 3 * sr until n) assertEquals(0f, out[i], 0f)
        assertEquals(0.3f, out[3 * sr - sr / 10], 1e-6f)          // far from the edge: untouched
        assertTrue(out[3 * sr - 1] < 0.01f)                          // fade ends at the boundary
        assertTrue(RoutedRender.maxStep(out) < 0.01f)                // no click
    }
    @Test fun hardMuteBeatsVadContext() {
        val x = FloatArray(4096) { 0.2f }
        val voicedFlags = BooleanArray(8) { it < 4 }                  // voiced context reaches frame 4..6
        val hm = BooleanArray(8) { it >= 4 }
        val out = RoutedRender.protect(x, x, voicedFlags, hardMute = hm)
        assertEquals(0f, RoutedRender.rms(out, 5 * 512, 4096), 1e-6f)
    }

    @Test fun extractorEnergyInOtherSpeakerBinsIsRejected() {
        // hallucinated target audio in OTHER_ONLY bins is pulled into the neighbouring crossfade; the strict band gate must catch it
        val extracted = FloatArray(n) { tone(500.0, 0.28f, it) }
        val r = FootageRenderPipeline.run(source, extracted, null, labels, voiced())
        assertTrue(r is RenderOutcome.Rejected)
    }
}
