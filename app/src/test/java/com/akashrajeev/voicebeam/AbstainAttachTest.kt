package com.akashrajeev.voicebeam.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AbstainAttachTest {
    private fun v(angle: Double) = floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat(), 0f)

    /** idx 0-5 cluster 0 (target, raw ~0 rad), 6-11 cluster 1 (other, raw ~2 rad), 12.. abstained. */
    private fun layout(nAbstained: Int): Pair<IntArray, List<Boolean>> {
        val assign = IntArray(12 + nAbstained) { if (it < 6) 0 else if (it < 12) 1 else -1 }
        return assign to List(assign.size) { true }
    }
    private fun rawOf(abstained: List<Double>): (Int) -> FloatArray? = { i ->
        when {
            i < 6 -> v(0.0 + (i % 3) * 0.02)
            i < 12 -> v(2.0 + (i % 3) * 0.02)
            else -> v(abstained[i - 12])
        }
    }

    @Test fun closeToTargetFarFromOtherAttaches() {
        val (asg, emb) = layout(1)
        val r = AbstainAttach.decide(emb, asg, 2, 0, rawOf(listOf(0.3)))
        assertEquals(listOf(12), r.map { it.index })
        assertTrue(r[0].cos >= AbstainAttach.MIN_COS); assertTrue(r[0].margin >= AbstainAttach.MIN_MARGIN)
    }
    @Test fun closerToOtherDoesNotAttach() {
        val (asg, emb) = layout(1)
        assertTrue(AbstainAttach.decide(emb, asg, 2, 0, rawOf(listOf(1.7))).isEmpty())
    }
    @Test fun midwayAmbiguousDoesNotAttach() {
        val (asg, emb) = layout(1)
        assertTrue(AbstainAttach.decide(emb, asg, 2, 0, rawOf(listOf(1.0))).isEmpty())
    }
    @Test fun lowCosineEvenWithMarginDoesNotAttach() {
        // -1.0 rad: cos to target 0.54 (under MIN_COS) and negative to the other cluster
        val (asg, emb) = layout(1)
        assertTrue(AbstainAttach.decide(emb, asg, 2, 0, rawOf(listOf(-1.0))).isEmpty())
    }
    @Test fun marginRuleIndependentOfCosine() {
        // two clusters close together: cos to target high but margin thin -> stay abstained
        val assign = intArrayOf(0, 0, 0, 1, 1, 1, -1)
        val emb = List(7) { true }
        val raw: (Int) -> FloatArray? = { i -> when { i < 3 -> v(0.0); i < 6 -> v(0.5); else -> v(0.2) } }
        assertTrue(AbstainAttach.decide(emb, assign, 2, 0, raw).isEmpty())
    }
    @Test fun clusteredAndNonEmbeddedWindowsAreNeverTouched() {
        val assign = IntArray(14) { if (it < 6) 0 else if (it < 12) 1 else -1 }
        val emb = List(14) { it != 13 }                 // 13 not embedded (speech under 50%): not a candidate
        val seen = HashSet<Int>()
        val raw: (Int) -> FloatArray? = { i -> seen.add(i); if (i < 6) v(0.0) else if (i < 12) v(2.0) else v(0.1) }
        val r = AbstainAttach.decide(emb, assign, 2, 0, raw)
        assertEquals(listOf(12), r.map { it.index })
        assertFalse(seen.contains(13))
    }
    @Test fun noAbstainedWindowsMeansNoRawEmbedCalls() {
        val assign = IntArray(12) { if (it < 6) 0 else 1 }
        var calls = 0
        val r = AbstainAttach.decide(List(12) { true }, assign, 2, 0, raw = { calls++; v(0.0) })
        assertTrue(r.isEmpty()); assertEquals(0, calls)
    }
    @Test fun singleClusterOrMissingCentroidIsNeutral() {
        val (asg, emb) = layout(1)
        assertTrue(AbstainAttach.decide(emb, asg, 1, 0, rawOf(listOf(0.1))).isEmpty())
        // raw unavailable for the other cluster's windows -> cannot state a margin -> nothing attaches
        val raw: (Int) -> FloatArray? = { i -> if (i in 6..11) null else if (i < 6) v(0.0) else v(0.1) }
        assertTrue(AbstainAttach.decide(emb, asg, 2, 0, raw).isEmpty())
    }
    @Test fun nonFiniteOrZeroVectorsAreIgnored() {
        val (asg, emb) = layout(2)
        val raw: (Int) -> FloatArray? = { i -> when { i == 12 -> floatArrayOf(Float.NaN, 0f, 0f); i == 13 -> floatArrayOf(0f, 0f, 0f); i < 6 -> v(0.0); else -> v(2.0) } }
        assertTrue(AbstainAttach.decide(emb, asg, 2, 0, raw).isEmpty())
    }
    @Test fun candidateAndCentroidCostIsBounded() {
        val n = 100
        val assign = IntArray(40 + n) { if (it < 20) 0 else if (it < 40) 1 else -1 }
        var calls = 0
        AbstainAttach.decide(List(assign.size) { true }, assign, 2, 0, raw = { i -> calls++; if (i < 20) v(0.0) else if (i < 40) v(2.0) else v(0.1) })
        assertTrue("calls=$calls", calls <= 2 * AbstainAttach.MAX_CENTROID_WINDOWS + AbstainAttach.MAX_CANDIDATES)
    }
    @Test fun multipleCandidatesDecidedIndependently() {
        val (asg, emb) = layout(3)
        val r = AbstainAttach.decide(emb, asg, 2, 0, rawOf(listOf(0.2, 1.8, 0.1)))
        assertEquals(listOf(12, 14), r.map { it.index })
    }
}
