package com.akashrajeev.voicebeam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DpdfnetDspTest {
    private val sr = 16000
    private fun sig(n: Int) = FloatArray(n) { i -> (sin(2 * PI * 440 * i / sr) + 0.5 * sin(2 * PI * 1234 * i / sr)).toFloat() }
    private val identity = DpdfnetDsp.FrameStep { re, im, oRe, oIm -> re.copyInto(oRe); im.copyInto(oIm) }

    @Test fun windowMatchesSherpaVorbis() {
        val w = DpdfnetDsp.window
        assertEquals(3.7849153e-05f, w[0], 1e-7f)
        assertEquals(1.0f, w[160], 1e-6f)
        assertEquals(3.7849153e-05f, w[319], 1e-7f)
        assertEquals(0.7125379f, w[80], 1e-5f)
    }
    @Test fun stftMatchesNumpyReference() {
        val x = sig(1600)
        val p = DpdfnetDsp.reflectPad(x)
        val re = FloatArray(DpdfnetDsp.BINS); val im = FloatArray(DpdfnetDsp.BINS)
        DpdfnetDsp.dftFrame(p, 2 * DpdfnetDsp.HOP, re, im)   // frame 2 of a centered STFT
        assertEquals(0.0697228f, re[0], 1e-2f)
        assertEquals(88.861336f, re[9], 5e-2f); assertEquals(29.060436f, im[9], 5e-2f)
        assertEquals(-7.8144083f, re[26], 5e-2f); assertEquals(3.5506225f, im[26], 5e-2f)
        assertEquals(0f, im[0], 1e-4f)
    }
    @Test fun frameCountIsOnePlusLenOverHop() { assertEquals(11, DpdfnetDsp.frameCount(1600)); assertEquals(101, DpdfnetDsp.frameCount(16000)) }
    @Test fun identityStepIsExactlyTheInputShiftedLeft() {
        val x = sig(3200)
        val y = DpdfnetDsp.run(x, identity)
        assertEquals(x.size, y.size)
        for (i in 0 until x.size - DpdfnetDsp.SHIFT) assertEquals("i=$i", x[i + DpdfnetDsp.SHIFT], y[i], 2e-3f)
        for (i in x.size - DpdfnetDsp.SHIFT until x.size) assertEquals(0f, y[i], 0f)       // zero fill
    }
    @Test fun halfGainStepHalvesTheOutput() {
        val x = sig(3200)
        val y = DpdfnetDsp.run(x, step = DpdfnetDsp.FrameStep { re, im, oRe, oIm -> for (k in re.indices) { oRe[k] = re[k] * 0.5f; oIm[k] = im[k] * 0.5f } })
        for (i in 0 until x.size - DpdfnetDsp.SHIFT step 7) assertEquals(0.5f * x[i + DpdfnetDsp.SHIFT], y[i], 2e-3f)
    }
    @Test fun stepIsCalledOncePerFrameInOrder() {
        val x = sig(1600); var calls = 0
        DpdfnetDsp.run(x, step = DpdfnetDsp.FrameStep { re, im, oRe, oIm -> calls++; re.copyInto(oRe); im.copyInto(oIm) })
        assertEquals(DpdfnetDsp.frameCount(1600), calls)
    }
    @Test(expected = kotlinx.coroutines.CancellationException::class) fun cancelThrows() {
        var n = 0
        DpdfnetDsp.run(sig(16000), identity, isCancelled = { n++ > 3 })
    }
    @Test(expected = IllegalArgumentException::class) fun tooShortRejected() { DpdfnetDsp.run(FloatArray(100), identity) }
    @Test(expected = IllegalArgumentException::class) fun nonFiniteRejected() { DpdfnetDsp.run(FloatArray(3200) { Float.NaN }, identity) }
    @Test fun progressReports() {
        var last = -1
        DpdfnetDsp.run(sig(16000), identity, onProgress = { d, t -> last = d; assertTrue(d < t) })
        assertTrue(last >= 0)
    }
}
