package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import org.junit.Assert.*
import org.junit.Test

class FaceVisibilityGateTest {
    private fun labels(s: String) = Array(s.length) { when (s[it]) { 'T' -> Seg.TARGET_ONLY; 'O' -> Seg.OTHER_ONLY; 'V' -> Seg.OVERLAP; else -> Seg.NONE } }
    private fun str(a: Array<Seg>) = a.map { when (it) { Seg.TARGET_ONLY -> 'T'; Seg.OTHER_ONLY -> 'O'; Seg.OVERLAP -> 'V'; Seg.NONE -> 'N' } }.joinToString("")
    private fun vis(s: String) = BooleanArray(s.length) { s[it] == '1' }

    @Test fun typedReferenceKeepsAudioLabels() {
        assertTrue(FaceVisibilityGate.relabel(labels("TTTT"), null) is FaceVisibilityGate.Result.Unchanged)
    }
    @Test fun faceGoneMutesTargetAndOverlapBinsButKeepsHoldBin() {
        // face seen bins 0-9, gone after; hold of 1 keeps bin 10 visible
        val r = FaceVisibilityGate.relabel(labels("TTTTTTTTTTTTTVOO"), vis("1111111111000000")) as FaceVisibilityGate.Result.Relabeled
        assertEquals("TTTTTTTTTTTOOOOO", str(r.labels))
        assertEquals('T', str(r.labels)[10]); assertEquals('O', str(r.labels)[11]); assertEquals('O', str(r.labels)[13]); assertEquals('O', str(r.labels)[14])
    }
    @Test fun overlapKeptWhenAskedNotToMuteIt() {
        val r = FaceVisibilityGate.relabel(labels("TTTTTTTTVOO"), vis("11111111000"), muteOverlap = false)
        assertTrue(r is FaceVisibilityGate.Result.Unchanged || str((r as FaceVisibilityGate.Result.Relabeled).labels)[8] == 'V')
    }
    @Test fun briefDropoutIsNotChopped() {
        val r = FaceVisibilityGate.relabel(labels("TTTTTTTT"), vis("11101111"))
        assertTrue(r is FaceVisibilityGate.Result.Unchanged)
    }
    @Test fun sparseTrackFallsBackToAudioOnly() {
        val r = FaceVisibilityGate.relabel(labels("TTTTTTTTTT"), vis("1000000000"))
        assertTrue(r is FaceVisibilityGate.Result.Unchanged); assertTrue((r as FaceVisibilityGate.Result.Unchanged).reason.contains("sparse"))
    }
    @Test fun nonTargetBinsNeverBecomeTarget() {
        val l = labels("NOTTTTTVOON"); val r = FaceVisibilityGate.relabel(l, vis("00111110000"))
        val out = if (r is FaceVisibilityGate.Result.Relabeled) r.labels else l
        for (i in l.indices) if (l[i] != Seg.TARGET_ONLY) assertTrue(out[i] != Seg.TARGET_ONLY)
    }

    @Test fun neverMutesEverything() {
        val r = FaceVisibilityGate.relabel(labels("OOTTTT"), vis("110000"))
        assertTrue(r is FaceVisibilityGate.Result.Unchanged)
    }
    @Test fun oneBinRoundingGapAtTheEndIsNotMuted() {
        val r = FaceVisibilityGate.relabel(labels("TTTTTTTT"), vis("1111111"))
        assertTrue(r is FaceVisibilityGate.Result.Unchanged)
    }
}
