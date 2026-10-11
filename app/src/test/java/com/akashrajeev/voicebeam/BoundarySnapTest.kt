package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import org.junit.Assert.*
import org.junit.Test

class BoundarySnapTest {
    private val sr = 16000
    private val binLen = sr / 2
    /** 4 s: T T T T O O O O (edge at 2.0 s = sample 32000, VAD frame 62.5). */
    private val labels = arrayOf(Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY)
    private val n = sr * 4
    private val frames = (n + 511) / 512
    private fun speechExcept(vararg pauses: IntRange) = BooleanArray(frames) { f -> pauses.none { f in it } }
    private val sig = FloatArray(n) { 0.1f }

    @Test fun edgeInsideSpeechMovesToNearbyPause_earlierShrinksKept() {
        // pause at frames 56-59 (centre 58*512 = 29696, 0.144 s before the 2.0 s edge)
        val m = BoundarySnap.mask(labels, speechExcept(56..59), n)
        assertEquals(1, m.movedEdges)
        assertTrue(m.keep[29000]); assertFalse(m.keep[29900]); assertFalse(m.keep[31900])
    }
    @Test fun laterPauseExtendsKeptIntoOtherBinButOnlyToThePause() {
        val m = BoundarySnap.mask(labels, speechExcept(65..68), n)                  // centre 67*512 = 34304 (0.144 s after the edge)
        assertTrue(m.keep[33000]); assertFalse(m.keep[34900]); assertEquals(1, m.extendEdges.size)
        assertFalse(m.origKeep[33000])
    }
    @Test fun noPauseNearbyKeepsTheGrid() {
        val m = BoundarySnap.mask(labels, speechExcept(20..23), n)                  // far away
        assertEquals(0, m.movedEdges); for (i in 0 until n) assertEquals(m.origKeep[i], m.keep[i])
    }
    @Test fun edgeAlreadyInAPauseStays() {
        val m = BoundarySnap.mask(labels, speechExcept(60..65), n)
        assertEquals(0, m.movedEdges)
    }
    @Test fun noneEdgesNeverMove() {
        val l = labels.copyOf(); for (i in 4..7) l[i] = Seg.NONE
        assertEquals(0, BoundarySnap.mask(l, speechExcept(56..59), n).movedEdges)
    }
    @Test fun tooFarPauseOrTooShortPauseIgnored() {
        assertEquals(0, BoundarySnap.mask(labels, speechExcept(40..43), n).movedEdges)    // 0.4 s away, beyond 0.2 s
        assertEquals(0, BoundarySnap.mask(labels, speechExcept(57..58), n).movedEdges)    // 2 frames < 3
    }
    @Test fun mirroredEdgeOtherThenTarget() {
        val l = arrayOf(Seg.OTHER_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY)
        val m = BoundarySnap.mask(l, speechExcept(56..59), n)                       // pause centre 29696 is BEFORE the edge: kept side (right) extends left
        assertTrue(m.keep[30500]); assertFalse(m.keep[29000])
    }
    @Test fun twoEdgesInsideOneBinNeverCross() {
        val l = arrayOf(Seg.TARGET_ONLY, Seg.OTHER_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY)
        val m = BoundarySnap.mask(l, speechExcept(18..21, 26..29), n)               // both edges extend into the one O bin
        assertTrue(m.keep[9000]); assertTrue(m.keep[15000]); assertFalse(m.keep[12000])   // both edges moved, the middle of the O bin stays muted
    }
    @Test fun composeUsesPrimaryOnGridExtensionOnSnapAndZeroWhenMuted() {
        val primary = FloatArray(n) { 0.5f }; val ext = FloatArray(n) { 0.25f }
        val m = BoundarySnap.mask(labels, speechExcept(65..68), n)
        val o = BoundarySnap.compose(primary, ext, m)
        assertEquals(0.5f, o[10000], 1e-6f)                                         // grid-kept, far from edges
        assertEquals(0.25f, o[33000], 1e-6f)                                        // snapped extension
        assertEquals(0f, o[35000], 0f); assertEquals(0f, o[n - 1], 0f)
        assertEquals(0.25f, o[32400], 1e-6f)                                        // seam of the extended edge uses the plain base
    }
    @Test fun composeFadesWithoutClicks() {
        val primary = FloatArray(n) { 0.5f }
        val m = BoundarySnap.mask(labels, speechExcept(20..23), n)
        val o = BoundarySnap.compose(primary, primary, m)
        var step = 0f; for (i in 1 until n) step = maxOf(step, Math.abs(o[i] - o[i - 1])); assertTrue(step < 0.01f)
        assertEquals(0f, o[binLen * 4], 0f)
    }
    @Test(expected = IllegalArgumentException::class) fun mustCoverAudio() { BoundarySnap.mask(labels, speechExcept(), n * 2) }
}
