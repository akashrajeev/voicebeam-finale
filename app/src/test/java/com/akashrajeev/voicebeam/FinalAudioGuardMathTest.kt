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
        assertFalse(FinalAudioGuardMath.pass(0.2f, 0.30f, 3))
        assertTrue(FinalAudioGuardMath.pass(0.8f, 0.7f, 3))           // homogeneous source loses < .45
        assertFalse(FinalAudioGuardMath.pass(0.9f, 0.4f, 3))          // homogeneous source loses > .45
        assertFalse(FinalAudioGuardMath.pass(0.9f, 0.9f, 0))          // nothing scored -> fail
        assertFalse(FinalAudioGuardMath.pass(Float.NaN, 0.9f, 2))
    }
    @Test fun measuredLaptopCases() {
        assertTrue(FinalAudioGuardMath.pass(0.707f, 0.471f, 3))       // dashcam-like honest fallback
        assertFalse(FinalAudioGuardMath.pass(0.666f, 0.137f, 3))      // venue-like true fail (rule 2: gap .529)
        assertFalse(FinalAudioGuardMath.pass(0.347f, 0.293f, 3))      // clip2-like true fail (rule 1)
        assertFalse(FinalAudioGuardMath.pass(0.5f, 0.210f, 3))        // clip7-like weak fallback (rule 1)
        assertTrue(FinalAudioGuardMath.pass(0.5f, 0.352f, 3))         // honest minimum
        assertFalse(FinalAudioGuardMath.pass(0.5f, 0.269f, 3))        // noisy-reference true fail
    }
    @Test(expected = IllegalArgumentException::class) fun labelsMustCoverChunk() {
        FinalAudioGuardMath.selectedFraction(six(Seg.NONE), 0, 48000)
    }

    private fun fa(vararg x: Float) = x
    @Test fun fragmentedRenderRejectsDespiteMeanPass() {
        val o = fa(0.56f, 0.29f, 0.95f, 0.41f, 0.20f, 0.22f)   // mean 0.438 passes the mean rule, but 2/6 chunks < .25
        assertTrue(FinalAudioGuardMath.pass(0.5f, o.average().toFloat(), o.size))
        assertFalse(FinalAudioGuardMath.passChunks(fa(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f), o))
    }
    @Test fun honestSetsPassChunkFloor() {
        assertTrue(FinalAudioGuardMath.passChunks(fa(0.6f, 0.6f, 0.6f), fa(0.55f, 0.60f, 0.52f)))        // clip3-like
        assertTrue(FinalAudioGuardMath.passChunks(fa(0.4f, 0.4f, 0.4f, 0.4f), fa(0.40f, 0.36f, 0.45f, 0.38f))) // clip6-like
        assertTrue(FinalAudioGuardMath.passChunks(fa(0.707f, 0.707f), fa(0.471f, 0.50f)))                     // dashcam-like
    }
    @Test fun exactlyQuarterBelowIsAllowedMoreIsNot() {
        assertFalse(FinalAudioGuardMath.tooManyWeakChunks(fa(0.2f, 0.5f, 0.6f, 0.7f)))                    // 1/4 -> allowed
        assertTrue(FinalAudioGuardMath.tooManyWeakChunks(fa(0.2f, 0.2f, 0.6f, 0.7f, 0.7f)))               // 2/5 -> reject
        assertTrue(FinalAudioGuardMath.tooManyWeakChunks(fa(0.2f, 0.2f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f)).not()) // 2/8 = 25% -> allowed
        assertTrue(FinalAudioGuardMath.tooManyWeakChunks(fa()))
        assertFalse(FinalAudioGuardMath.tooManyWeakChunks(fa(0.25f, 0.25f)))                              // exactly at floor is not below
    }
    @Test fun mismatchedOrEmptyChunkArraysFail() {
        assertFalse(FinalAudioGuardMath.passChunks(fa(0.5f), fa(0.5f, 0.5f)))
        assertFalse(FinalAudioGuardMath.passChunks(fa(), fa()))
    }
}
