package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class OutputGatesTest {
    private val sr = 16000
    private fun tone(sec: Float, hz: Double, amp: Float) = FloatArray((sec * sr).toInt()) { (amp * sin(2 * PI * hz * it / sr)).toFloat() }
    private fun add(a: FloatArray, b: FloatArray) = FloatArray(a.size) { a[it] + b[it] }
    private fun scale(a: FloatArray, g: Float) = FloatArray(a.size) { a[it] * g }
    private fun labels(n: Int, s: Seg) = Array(n) { s }
    private fun voiced(sec: Float) = BooleanArray((sec * sr / 512).toInt() + 2) { true }

    @Test fun honestAttenuationPasses() {
        val src = add(tone(2f, 500.0, 0.3f), tone(2f, 3000.0, 0.3f))
        val r = OutputGates.check(scale(src, 0.85f), src, labels(4, Seg.TARGET_ONLY))
        assertTrue(r.failures.toString(), r.passed)
        assertTrue(r.retentionDb > -2f)
    }
    @Test fun retentionLossFails() {
        val src = tone(2f, 500.0, 0.3f)
        val r = OutputGates.check(scale(src, 0.5f), src, labels(4, Seg.TARGET_ONLY)) // -6 dB
        assertFalse(r.passed); assertTrue(r.retentionDb < -5f)
    }
    @Test fun retentionBoundaryJustInsideAndOutside() {
        val src = tone(2f, 500.0, 0.3f)
        assertTrue(OutputGates.check(scale(src, 10f.pow(-2.5f / 20f)), src, labels(4, Seg.OVERLAP)).passed)
        assertFalse(OutputGates.check(scale(src, 10f.pow(-3.5f / 20f)), src, labels(4, Seg.OVERLAP)).passed)
    }
    @Test fun otherOnlyBinsDoNotCountTowardRetention() {
        val src = tone(2f, 500.0, 0.3f)
        val out = FloatArray(src.size) { if (it < sr) src[it] * (if (it >= sr - 160) (sr - 1 - it) / 160f else 1f) else 0f } // second second removed, 10 ms fade ending at the edge
        val l = arrayOf(Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY)
        assertTrue(OutputGates.check(out, src, l).passed)
    }
    @Test fun amplifiedNoneBinsFail() {
        val src = tone(2f, 500.0, 0.02f)
        val r = OutputGates.check(scale(src, 7f), src, labels(4, Seg.NONE))
        assertFalse(r.passed); assertTrue(r.noneRatio!! > 0.05f)
    }
    @Test fun quietNoneBinsPass() {
        val src = tone(2f, 500.0, 0.02f)
        val r = OutputGates.check(scale(src, 0.014f), src, labels(4, Seg.NONE))
        assertTrue(r.failures.toString(), r.passed)
    }
    @Test fun narrowbandFabricationFailsBandCapEvenWhenBroadbandIsLower() {
        val src = add(tone(2f, 3000.0, 0.5f), tone(2f, 500.0, 0.01f))
        val out = tone(2f, 500.0, 0.3f)            // broadband RMS 0.21 < src 0.35, but 500 Hz band fabricated
        val r = OutputGates.check(out, src, labels(4, Seg.OVERLAP))
        assertFalse(r.passed); assertTrue(r.worstBandExcessDb > 1f)
        assertTrue(RoutedRender.rms(out) < RoutedRender.rms(src))
    }
    @Test fun highFrequencyBandIsNotCapped() {
        val src = tone(2f, 500.0, 0.3f)
        val out = add(scale(src, 0.9f), tone(2f, 6000.0, 0.05f))
        assertTrue(OutputGates.check(out, src, labels(4, Seg.TARGET_ONLY)).passed)
    }
    @Test fun fabricationIsCaughtRegardlessOfVadState() {
        val src = tone(2f, 3000.0, 0.5f); val out = tone(2f, 500.0, 0.3f)
        assertFalse(OutputGates.check(out, src, labels(4, Seg.OTHER_ONLY)).passed)
    }
    @Test fun lowSourceBandFabricationIsNotHiddenByAFloor() {
        val src = add(tone(2f, 3000.0, 0.5f), tone(2f, 500.0, 0.0005f)) // 500 Hz nearly empty in the source
        val out = add(scale(tone(2f, 3000.0, 0.5f), 0.9f), tone(2f, 500.0, 0.05f))
        assertFalse(OutputGates.check(out, src, labels(4, Seg.TARGET_ONLY)).passed)
    }
    @Test fun finalPartialFrameIsChecked() {
        val len = 512 * 10 + 200
        val src = FloatArray(len) { (0.3f * sin(2 * PI * 3000.0 * it / sr)).toFloat() }
        val out = FloatArray(len) { if (it >= 512 * 10) (0.5f * sin(2 * PI * 500.0 * it / sr)).toFloat() else src[it] * 0.9f }
        assertFalse(OutputGates.check(out, src, labels(1, Seg.TARGET_ONLY)).passed)
    }
    @Test(expected = IllegalArgumentException::class) fun lengthMismatch() { OutputGates.check(FloatArray(10), FloatArray(11), labels(4, Seg.NONE)) }
    @Test(expected = IllegalArgumentException::class) fun labelsMustCover() { OutputGates.check(tone(2f, 500.0, 0.1f), tone(2f, 500.0, 0.1f), labels(1, Seg.NONE)) }
    @Test(expected = IllegalArgumentException::class) fun nanRejected() {
        val x = tone(1f, 500.0, 0.1f); x[3] = Float.NaN; OutputGates.check(x, tone(1f, 500.0, 0.1f), labels(2, Seg.NONE))
    }
    @Test(expected = IllegalArgumentException::class) fun wrongSampleRateRejected() {
        OutputGates.check(FloatArray(8), FloatArray(8), labels(1, Seg.NONE), sampleRate = 8000)
    }
    @Test fun bandEnergyPlacesTonesInTheRightBand() {
        val e = OutputGates.bandEnergy(tone(0.1f, 1500.0, 0.5f), 0)
        assertTrue(e[1] > 10 * (e[0] + e[2] + e[3]))
        val h = OutputGates.bandEnergy(tone(0.1f, 6000.0, 0.5f), 0)
        assertTrue(h[3] > 10 * (h[0] + h[1] + h[2]))
    }

    @Test fun hardCutClickIsFlaggedButFadedCutPasses() {
        val src = tone(2f, 500.0, 0.3f)
        val hard = FloatArray(src.size) { if (it < sr) src[it] else 0f }
        val l = arrayOf(Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY)
        assertFalse(OutputGates.check(hard, src, l).passed)
    }
}
