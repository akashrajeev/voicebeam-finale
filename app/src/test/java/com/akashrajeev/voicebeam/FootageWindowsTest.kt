package com.akashrajeev.voicebeam.core

import org.junit.Assert.*
import org.junit.Test

class FootageWindowsTest {
    @Test fun windowCountAndTimes() {
        val w = FootageWindows.embedWindows(FloatArray(16000 * 5), embed = { floatArrayOf(1f) })!!
        assertEquals(8, w.size) // (5-1.5)/0.5+1
        assertEquals(0f, w[0].startSec, 1e-6f); assertEquals(1.5f, w[0].endSec, 1e-6f)
        assertEquals(3.5f, w.last().startSec, 1e-6f); assertEquals(5f, w.last().endSec, 1e-6f)
    }
    @Test fun shortAudioEmpty() { assertTrue(FootageWindows.embedWindows(FloatArray(100), embed = { floatArrayOf(1f) })!!.isEmpty()) }
    @Test fun nullEmbeddingPreserved() {
        var n = 0
        val w = FootageWindows.embedWindows(FloatArray(16000 * 4), embed = { if (n++ == 1) null else floatArrayOf(1f) })!!
        assertNull(w[1].emb); assertNotNull(w[0].emb)
    }
    @Test fun cancelReturnsNull() {
        var n = 0
        assertNull(FootageWindows.embedWindows(FloatArray(16000 * 5), embed = { floatArrayOf(1f) }, isCancelled = { n++ >= 2 }))
    }
    @Test fun progressReported() {
        var last = 0; var tot = 0
        FootageWindows.embedWindows(FloatArray(16000 * 3), embed = { floatArrayOf(1f) }, onProgress = { d, t -> last = d; tot = t })
        assertEquals(tot, last); assertTrue(tot > 0)
    }
    @Test fun binLipAveragesAndMarksMissing() {
        val b = FootageWindows.binLip(floatArrayOf(0.1f, 0.3f, 1.2f, Float.NaN, 9f), floatArrayOf(0.2f, 0.4f, 0.9f, 0.5f, 0.5f), 2f)
        assertEquals(4, b.size)
        assertEquals(0.3f, b[0], 1e-5f); assertTrue(b[1].isNaN()); assertEquals(0.9f, b[2], 1e-5f); assertTrue(b[3].isNaN())
    }
    @Test(expected = IllegalArgumentException::class) fun binLipLengthMismatch() { FootageWindows.binLip(FloatArray(2), FloatArray(3), 2f) }
    @Test fun nanIsNeverOnOrOff() {
        val on = FootageWindows.lipOn(floatArrayOf(Float.NaN, 0.9f, 0.1f), 0.5f)
        assertArrayEquals(booleanArrayOf(false, true, false), on)
        val off = FootageWindows.othersOff(listOf(floatArrayOf(Float.NaN, 0.1f, 0.9f)), 0.3f)!!
        assertArrayEquals(booleanArrayOf(false, true, false), off)
        assertNull(FootageWindows.othersOff(emptyList(), 0.3f))
    }

    @Test(expected = IllegalArgumentException::class) fun tinySampleRateRejected() {
        FootageWindows.embedWindows(FloatArray(100), sampleRate = 1, embed = { floatArrayOf(1f) })
    }
    @Test fun cancelDuringLastEmbedReturnsNull() {
        var cancelled = false
        val r = FootageWindows.embedWindows(FloatArray(16000 * 2), embed = { cancelled = true; floatArrayOf(1f) }, isCancelled = { cancelled })
        assertNull(r)
    }
}
