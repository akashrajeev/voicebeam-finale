package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import org.junit.Assert.*
import org.junit.Test

class SilenceDuckTest {
    @Test fun ducksOtherBinsToFloorNotSilence() {
        val sr = 16000; val bin = (FootageAnalysis.BIN_SEC * sr).toInt()
        val labels = arrayOf(Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY)
        val audio = FloatArray(bin * 6) { 0.5f }; val bed = FloatArray(bin * 6) { 0.4f }
        val o = SilenceRender.duck(audio, bed, labels, sr)
        assertEquals(0.5f, o[bin / 2], 1e-6f)                       // target kept
        assertEquals(0.4f * SilenceRender.DUCK_GAIN, o[bin * 2 + bin / 2], 1e-6f) // ducked, not zero
        assertTrue(o[bin * 2 + bin / 2] > 0f)
        assertEquals(0.5f, o[bin * 5], 1e-6f)                        // voice returns
        val edge = o[bin * 2 - 1]; assertTrue(edge < 0.5f && edge > 0.4f * SilenceRender.DUCK_GAIN - 1e-6f) // fades, no step
    }
}
