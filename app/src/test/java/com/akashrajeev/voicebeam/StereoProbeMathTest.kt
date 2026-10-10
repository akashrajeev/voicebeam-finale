package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.StereoProbeMath.Verdict
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class StereoProbeMathTest {
    private val n = 8000
    private fun noise(seed: Int, amp: Float): FloatArray { var s = seed.toLong(); return FloatArray(n) { s = s * 6364136223846793005L + 1442695040888963407L; amp * (((s ushr 40).toInt() and 0xFFFF) / 32767.5f - 1f) } }
    private fun shifted(x: FloatArray, lag: Int, g: Float) = FloatArray(x.size) { i -> val j = i - lag; if (j in x.indices) x[j] * g else 0f }

    @Test fun identicalChannelsAreDuplicate() { val x = noise(1, 0.1f); assertEquals(Verdict.DUPLICATE_CHANNELS, StereoProbeMath.analyze(x, x.copyOf()).verdict) }
    @Test fun silentIsTooQuiet() { assertEquals(Verdict.TOO_QUIET, StereoProbeMath.analyze(FloatArray(n), FloatArray(n)).verdict) }
    @Test fun deadChannelDetected() { assertEquals(Verdict.ONE_CHANNEL_DEAD, StereoProbeMath.analyze(noise(2, 0.1f), FloatArray(n)).verdict) }
    @Test fun levelDifferenceIsDistinctAndMeasured() {
        val x = noise(3, 0.1f); val r = StereoProbeMath.analyze(x, FloatArray(n) { x[it] * 0.5f })
        assertEquals(Verdict.DISTINCT_CHANNELS, r.verdict); assertEquals(6.02f, r.ildDb, 0.1f); assertEquals(0, r.bestLagSamples)
    }
    @Test fun arrivalDelayIsFoundWithSign() {
        val x = noise(4, 0.1f); val r = StereoProbeMath.analyze(x, shifted(x, 5, 1f))
        assertEquals(5, r.bestLagSamples); assertTrue(r.peakCorrelation > 0.95f); assertEquals(Verdict.DISTINCT_CHANNELS, r.verdict)
        assertEquals(-5, StereoProbeMath.analyze(shifted(x, 5, 1f), x).bestLagSamples)
    }
    @Test fun twoSpeakersSeparableOnlyIfDirectionsDiffer() {
        val x = noise(5, 0.1f); val a = StereoProbeMath.analyze(x, shifted(x, 4, 0.8f)); val b = StereoProbeMath.analyze(x, shifted(x, -4, 1.3f))
        assertTrue(StereoProbeMath.speakersDirectionallySeparable(a, b))
        assertFalse(StereoProbeMath.speakersDirectionallySeparable(a, a))
        val dup = StereoProbeMath.analyze(x, x.copyOf())
        assertFalse(StereoProbeMath.speakersDirectionallySeparable(dup, b))
    }
    @Test(expected = IllegalArgumentException::class) fun lengthMismatchRejected() { StereoProbeMath.analyze(FloatArray(2000), FloatArray(1500)) }
    @Test(expected = IllegalArgumentException::class) fun tooShortRejected() { StereoProbeMath.analyze(FloatArray(100), FloatArray(100)) }
}
