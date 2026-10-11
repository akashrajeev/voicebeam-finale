package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import org.junit.Assert.*
import org.junit.Test

class FaceVoiceCheckTest {
    private val T = Seg.TARGET_ONLY; private val O = Seg.OTHER_ONLY; private val N = Seg.NONE
    @Test fun mismatchWhenLipsMoveButOnlyOtherVoice() {
        val labels = Array(10) { O }; val lip = BooleanArray(10) { true }
        assertTrue(FaceVoiceCheck.crossCheck(labels, lip) is FaceVoiceCheck.Check.Mismatch)
    }
    @Test fun okWhenTargetPresentOrTooFewBins() {
        val lip = BooleanArray(10) { true }
        assertTrue(FaceVoiceCheck.crossCheck(Array(10) { T }, lip) is FaceVoiceCheck.Check.Ok)
        assertTrue(FaceVoiceCheck.crossCheck(Array(3) { O }, BooleanArray(3) { true }) is FaceVoiceCheck.Check.Ok)
        assertTrue(FaceVoiceCheck.crossCheck(Array(10) { O }, null) is FaceVoiceCheck.Check.Ok)
    }
    @Test fun fillsShortGapsInsideTargetSpeechWithLips() {
        val labels = arrayOf(T, T, O, N, T, T, O, O, O, T)
        val lip = BooleanArray(10) { true }
        val f = FaceVoiceCheck.fillGaps(labels, lip)
        assertEquals(2, f.bins); assertEquals(T, f.labels[2]); assertEquals(T, f.labels[3])
        assertEquals(O, f.labels[7])  // 3-bin gap stays
        lip[2] = false
        assertEquals(0, FaceVoiceCheck.fillGaps(arrayOf(T, O, T), booleanArrayOf(true, false, true)).bins)
    }
}
