package com.akashrajeev.voicebeam.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BandLimiterTest {
    private fun tone(n: Int, hz: Double, amp: Double) = FloatArray(n) { (amp * sin(2 * PI * hz * it / 16000)).toFloat() }
    private fun labels(n: Int) = Array(n / 8000 + 2) { FootageAnalysis.Seg.TARGET_ONLY }

    @Test fun louderFrameIsScaledDownAndPassesBandGate() {
        val src = tone(16000, 500.0, 0.1)
        val den = src.copyOf()
        for (i in 2048 until 2560) den[i] = den[i] * 2f   // +6 dB in one frame
        val before = OutputGates.check(den, src, labels(src.size))
        assertTrue(before.failures.any { it.startsWith("band excess") })
        val r = BandLimiter.limit(src, den)
        assertEquals(1, r.limitedFrames)
        val after = OutputGates.check(r.audio, src, labels(src.size))
        assertTrue(after.failures.none { it.startsWith("band excess") })
    }

    @Test fun quieterOutputIsUntouched() {
        val src = tone(16000, 500.0, 0.1)
        val den = FloatArray(src.size) { src[it] * 0.5f }
        val r = BandLimiter.limit(src, den)
        assertEquals(0, r.limitedFrames)
        assertArrayEquals(den, r.audio, 0f)
    }
}
