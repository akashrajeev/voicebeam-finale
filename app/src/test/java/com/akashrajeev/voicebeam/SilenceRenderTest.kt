package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SilenceRenderTest {
    private val sr = 16000
    private val labels = arrayOf(Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.NONE, Seg.NONE) // 4 s
    private val n = sr * 4
    private val tone = FloatArray(n) { (0.3 * sin(2 * PI * 500.0 * it / sr)).toFloat() }
    private fun rms(x: FloatArray, a: Int, b: Int): Float { var s = 0.0; for (i in a until b) s += x[i].toDouble() * x[i]; return Math.sqrt(s / (b - a)).toFloat() }

    @Test fun otherAndNoneBinsAreExactZeroTargetKept() {
        val o = SilenceRender.hardMute(tone, labels, sr)
        for (i in sr until 2 * sr) assertEquals(0f, o[i], 0f)
        for (i in 3 * sr until n) assertEquals(0f, o[i], 0f)
        assertTrue(rms(o, sr / 4, sr - sr / 4) > 0.15f)
    }
    @Test fun voiceReturnsWhenTheLabelReturns() {
        val o = SilenceRender.hardMute(tone, labels, sr)
        assertTrue(rms(o, 2 * sr + sr / 4, 3 * sr - sr / 4) > 0.15f)              // second target stretch is back at full level
        val relabeled = labels.copyOf(); relabeled[2] = Seg.TARGET_ONLY; relabeled[3] = Seg.TARGET_ONLY
        assertTrue(rms(SilenceRender.hardMute(tone, relabeled, sr), sr + sr / 4, 2 * sr - sr / 4) > 0.15f)
    }
    @Test fun fadesAreInsideKeptSamplesSoNothingLeaksIntoTheMute() {
        val o = SilenceRender.hardMute(tone, labels, sr)
        assertEquals(0f, o[sr], 0f); assertEquals(0f, o[sr - 1].let { if (Math.abs(it) < 1e-9f) 0f else it }, 1e-3f)
        var maxStep = 0f; for (i in 1 until n) maxStep = maxOf(maxStep, Math.abs(o[i] - o[i - 1]))
        assertTrue(maxStep < 0.1f)                                                   // no click (tone itself steps about 0.06)
    }
    @Test fun noiseFloorOptionIsDeterministic() {
        val a = SilenceRender.hardMute(tone, labels, sr, noiseFloorRms = 0.001f); val b = SilenceRender.hardMute(tone, labels, sr, noiseFloorRms = 0.001f)
        assertArrayEquals(a, b, 0f); assertEquals(0.001f, rms(a, sr + 100, 2 * sr - 100), 0.0003f)
    }
    @Test(expected = IllegalArgumentException::class) fun mustCoverAudio() { SilenceRender.hardMute(FloatArray(n * 2), labels, sr) }
    @Test(expected = IllegalArgumentException::class) fun rejectsTinyFade() { SilenceRender.hardMute(tone, labels, sr, fadeSec = 0f) }

    @Test fun makeupRestoresSpeechLevelOnlyAndIsClamped() {
        val speech = BooleanArray((n + 511) / 512) { true }
        val half = FloatArray(n) { tone[it] * 0.84f }                                // -1.5 dB
        assertEquals(1f / 0.84f, SilenceRender.makeupGain(tone, half, speech), 0.01f)
        val quiet = FloatArray(n) { tone[it] * 0.1f }
        assertEquals(SilenceRender.MAX_MAKEUP, SilenceRender.makeupGain(tone, quiet, speech), 1e-4f)   // never more than +3 dB
        assertEquals(1f, SilenceRender.makeupGain(tone, FloatArray(n) { tone[it] * 1.5f }, speech), 0f) // never attenuates
    }
    @Test fun makeupIgnoresNonSpeechFramesAndTinyInputs() {
        val speech = BooleanArray((n + 511) / 512) { false }
        assertEquals(1f, SilenceRender.makeupGain(tone, tone, speech), 0f)
        assertSame(tone, SilenceRender.scale(tone, 1f)); assertEquals(0.6f, SilenceRender.scale(tone, 2f)[tone.indexOfFirst { it > 0.29f }] , 0.02f)
    }

    @Test fun pipelineDefaultIsHardMuteAndFloorStillAvailable() {
        val src = FloatArray(n) { i -> when (labels[i / (sr / 2)]) { Seg.TARGET_ONLY -> (0.3 * sin(2 * PI * 500.0 * i / sr)).toFloat(); Seg.OTHER_ONLY -> (0.3 * sin(2 * PI * 1200.0 * i / sr)).toFloat(); else -> (0.002 * sin(2 * PI * 300.0 * i / sr)).toFloat() } }
        val ext = FloatArray(n) { if (labels[it / (sr / 2)] == Seg.TARGET_ONLY) (0.28 * sin(2 * PI * 500.0 * it / sr)).toFloat() else 0f }
        val voiced = BooleanArray((n + 511) / 512) { f -> labels[minOf(labels.size - 1, (f * 512 + 256) / (sr / 2))] != Seg.NONE }
        val d = FootageRenderPipeline.run(src, ext, null, labels, voiced) as RenderOutcome.Rendered
        for (i in sr until 2 * sr) assertEquals(0f, d.audio[i], 0f)
        val f = FootageRenderPipeline.run(src, ext, null, labels, voiced, silenceMode = SilenceMode.FLOOR) as RenderOutcome.Rendered
        assertTrue(rms(f.audio, 3 * sr + 100, n - 100) > 1e-5f)
    }
}
