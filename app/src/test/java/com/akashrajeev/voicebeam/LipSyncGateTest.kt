package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import org.junit.Assert.*
import org.junit.Test

class LipSyncGateTest {
    private fun labels(n: Int, t: IntRange) = Array(n) { if (it in t) Seg.TARGET_ONLY else Seg.NONE }

    @Test fun mutesTargetBinsFarFromLipActivity() {
        val l = labels(30, 0..29)
        val lip = BooleanArray(30) { it in 0..19 }
        val r = LipSyncGate.relabel(l, lip) as LipSyncGate.Result.Relabeled
        assertEquals(Seg.TARGET_ONLY, r.labels[21]); assertEquals(Seg.OTHER_ONLY, r.labels[25])
        assertTrue(r.mutedBins in 7..8)
    }
    @Test fun holdKeepsWordGaps() {
        val lip = BooleanArray(20) { it % 3 == 0 }
        assertTrue(LipSyncGate.relabel(labels(20, 0..19), lip) is LipSyncGate.Result.Unchanged)
    }
    @Test fun brokenLipSeriesIsNotTrusted() {
        val lip = BooleanArray(40) { it in 0..3 }
        assertTrue(LipSyncGate.relabel(labels(40, 0..39), lip) is LipSyncGate.Result.Unchanged)
        assertTrue(LipSyncGate.relabel(labels(40, 0..39), BooleanArray(40)) is LipSyncGate.Result.Unchanged)
    }
    @Test fun nullLipLeavesLabels() {
        assertTrue(LipSyncGate.relabel(labels(10, 0..9), null) is LipSyncGate.Result.Unchanged)
    }
}
