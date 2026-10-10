package com.akashrajeev.voicebeam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AudioAlignmentTest {
    private val sr = 16000
    private fun ramp(n: Int) = FloatArray(n) { (it + 1).toFloat() }

    @Test fun positiveOffsetPadsLeadingZeros() {
        val out = AudioAlignment.align(ramp(1000), 1000, sr, 100_000L) // audio starts 100 ms after video
        assertEquals(1600 + 1000, out.size)
        for (i in 0 until 1600) assertEquals(0f, out[i], 0f)
        assertEquals(1f, out[1600], 0f)            // first audio sample lands exactly at video t = 0.1 s
        assertEquals(1000f, out[out.size - 1], 0f)
    }
    @Test fun negativeOffsetTrimsLeadingAudio() {
        val out = AudioAlignment.align(ramp(1000), 1000, sr, -10_000L) // audio starts 10 ms before video
        assertEquals(1000 - 160, out.size)
        assertEquals(161f, out[0], 0f)             // sample 160 of the audio is at video t = 0
    }
    @Test fun zeroOffsetIsIdentity() {
        val x = ramp(500)
        val out = AudioAlignment.align(x, 500, sr, 0L)
        assertEquals(500, out.size); assertEquals(x[499], out[499], 0f)
    }
    @Test fun usesOnlyCountedSamples() {
        val out = AudioAlignment.align(ramp(2000), 800, sr, 50_000L)
        assertEquals(800 + 800, out.size)
    }
    @Test fun formerlyRejectedOffsetsNowAlign() {
        // 50 ms was the old throw threshold; 60 ms, 300 ms and -300 ms must now work
        for (us in longArrayOf(60_000L, 300_000L, -300_000L, 2_000_000L)) AudioAlignment.align(ramp(40000), 40000, sr, us)
    }
    @Test fun beyondCapStillThrows() {
        for (us in longArrayOf(2_000_001L, -2_000_001L, 5_000_000L)) {
            try { AudioAlignment.align(ramp(100), 100, sr, us); fail("expected throw for $us") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("offset")) }
        }
    }
    @Test fun trimBeyondAvailableYieldsEmptyNotCrash() {
        assertEquals(0, AudioAlignment.align(ramp(10), 10, sr, -1_000_000L).size)
    }
    @Test fun sampleAccurateAtNonStandardRate() {
        // 44.1 kHz, 23 ms: round(0.023*44100)=1014 samples
        assertEquals(1014, AudioAlignment.leadingSamples(23_000L, 44100))
    }
}
