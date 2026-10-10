package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import org.junit.Assert.*
import org.junit.Test

class FinalAudioGuardMathTest {
    private val speechAll = BooleanArray(200) { true }
    private fun six(vararg s: Seg) = arrayOf(*s)

    @Test fun exactlyHalfSelectedQualifiesWithLoudOtherContent() {
        val l = six(Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY)
        assertEquals(0.5f, FinalAudioGuardMath.selectedFraction(l, 0, 48000), 1e-6f)
        assertTrue(FinalAudioGuardMath.chunkQualifies(l, speechAll, 0, 48000))
    }
    @Test fun belowHalfSelectedDoesNotQualify() {
        val l = six(Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY, Seg.NONE)
        assertFalse(FinalAudioGuardMath.chunkQualifies(l, speechAll, 0, 48000))
    }
    @Test fun overlapCountsAsSelected() {
        val l = six(Seg.OVERLAP, Seg.OVERLAP, Seg.OVERLAP, Seg.OTHER_ONLY, Seg.NONE, Seg.NONE)
        assertTrue(FinalAudioGuardMath.chunkQualifies(l, speechAll, 0, 48000))
    }
    @Test fun lowSpeechChunkDoesNotQualify() {
        val l = six(Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY)
        assertFalse(FinalAudioGuardMath.chunkQualifies(l, BooleanArray(200) { it % 10 == 0 }, 0, 48000))
    }
    @Test fun passRuleThresholds() {
        assertTrue(FinalAudioGuardMath.pass(0.2f, 0.45f, 3))          // mixed source, output resembles reference
        assertFalse(FinalAudioGuardMath.pass(0.2f, 0.35f, 3))
        assertTrue(FinalAudioGuardMath.pass(0.8f, 0.7f, 3))           // homogeneous source loses < .15
        assertFalse(FinalAudioGuardMath.pass(0.8f, 0.6f, 3))          // homogeneous source loses > .15
        assertFalse(FinalAudioGuardMath.pass(0.9f, 0.9f, 0))          // nothing scored -> fail
        assertFalse(FinalAudioGuardMath.pass(Float.NaN, 0.9f, 2))
    }
    @Test(expected = IllegalArgumentException::class) fun labelsMustCoverChunk() {
        FinalAudioGuardMath.selectedFraction(six(Seg.NONE), 0, 48000)
    }
}
