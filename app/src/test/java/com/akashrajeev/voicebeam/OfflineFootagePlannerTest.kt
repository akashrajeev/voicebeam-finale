package com.akashrajeev.voicebeam.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class OfflineFootagePlannerTest {
    private fun vec(angle: Double, jitter: Double) = floatArrayOf(cos(angle + jitter).toFloat(), sin(angle + jitter).toFloat(), 0.05f)
    /** 20s: A 0-8 and 14-20, B 8-14. */
    private fun windows(): List<WindowEmbedding> {
        val out = ArrayList<WindowEmbedding>(); var t = 0f; var n = 0
        while (t + 1.5f <= 20f) {
            val a = (t + 0.75f) < 8f || (t + 0.75f) >= 14f
            out.add(WindowEmbedding(t, t + 1.5f, vec(if (a) 0.0 else 2.0, (n % 3) * 0.03))); t += 0.5f; n++
        }
        return out
    }
    private fun iv(a: Float, b: Float) = FootageAnalysis.Interval(a, b)
    private fun aLip() = FloatArray(40) { b -> if (b < 16 || b >= 28) 0.8f else 0.1f }

    @Test fun tapInsideSpeakerAPicksA() {
        val ws = windows(); val clusters = ClusteredAnalysis.of(ws, 20f).windowClusters()
        val r = OfflineFootagePlanner.plan(ws, 20f, tap = iv(1f, 6f)) as PlanResult.Plan
        assertEquals(TargetSource.TAP, r.source); assertEquals(clusters[0], r.target)
        assertEquals(1, r.reference.size); assertEquals(1f, r.reference[0].startSec, 0f)
    }
    @Test fun tapInsideSpeakerBPicksB() {
        val ws = windows(); val clusters = ClusteredAnalysis.of(ws, 20f).windowClusters()
        val r = OfflineFootagePlanner.plan(ws, 20f, tap = iv(8.5f, 13.5f)) as PlanResult.Plan
        assertEquals(clusters[20], r.target); assertNotEquals(clusters[0], r.target)
    }
    @Test fun shortTapAbstains() {
        assertAbstain(AbstainReason.REFERENCE_TOO_SHORT, OfflineFootagePlanner.plan(windows(), 20f, tap = iv(1f, 2.5f)))
    }
    @Test fun tapOverAbstainedWindowsAbstains() {
        val ws = windows().map { if (it.startSec in 2f..6f) WindowEmbedding(it.startSec, it.endSec, null) else it }
        assertAbstain(AbstainReason.TAP_NOT_IN_CLUSTER, OfflineFootagePlanner.plan(ws, 20f, tap = iv(2f, 6f)))
    }
    @Test(expected = IllegalArgumentException::class) fun tapOutsideAudioRejected() { OfflineFootagePlanner.plan(windows(), 20f, tap = iv(15f, 25f)) }
    @Test fun noTapNoLipRequiresTap() { assertAbstain(AbstainReason.TAP_REQUIRED, OfflineFootagePlanner.plan(windows(), 20f)) }
    @Test fun allAbstainedIsNoCluster() {
        val ws = windows().map { WindowEmbedding(it.startSec, it.endSec, null) }
        assertAbstain(AbstainReason.NO_CLUSTER, OfflineFootagePlanner.plan(ws, 20f, tap = iv(1f, 6f)))
    }
    @Test fun singleSpeakerTapAbstainsSingleCluster() {
        val ws = (0 until 30).map { WindowEmbedding(it * 0.5f, it * 0.5f + 1.5f, vec(0.0, (it % 3) * 0.03)) }
        assertAbstain(AbstainReason.SINGLE_CLUSTER, OfflineFootagePlanner.plan(ws, 16f, tap = iv(1f, 6f)))
    }
    @Test fun facePathAssignsAndBuildsReferenceFromTargetOnlyBins() {
        val ws = windows(); val clusters = ClusteredAnalysis.of(ws, 20f).windowClusters()
        val r = OfflineFootagePlanner.plan(ws, 20f, lipBinned = aLip()) as PlanResult.Plan
        assertEquals(TargetSource.FACE, r.source); assertEquals(clusters[0], r.target)
        val total = r.reference.sumOf { (it.endSec - it.startSec).toDouble() }
        assertTrue(total >= 3.0 && total <= 10.01)
        for (iv in r.reference) for (b in (iv.startSec / 0.5f).toInt() until (iv.endSec / 0.5f).toInt())
            assertEquals(FootageAnalysis.Seg.TARGET_ONLY, r.labels[b])
    }
    @Test fun ambiguousLipAbstains() {
        assertAbstain(AbstainReason.LIP_AMBIGUOUS, OfflineFootagePlanner.plan(windows(), 20f, lipBinned = FloatArray(40) { 0.5f }))
        assertAbstain(AbstainReason.LIP_AMBIGUOUS, OfflineFootagePlanner.plan(windows(), 20f, lipBinned = FloatArray(40) { Float.NaN }))
    }
    @Test fun tapWinsOverFace() {
        val ws = windows(); val clusters = ClusteredAnalysis.of(ws, 20f).windowClusters()
        val r = OfflineFootagePlanner.plan(ws, 20f, tap = iv(8.5f, 13.5f), lipBinned = aLip()) as PlanResult.Plan
        assertEquals(TargetSource.TAP, r.source); assertEquals(clusters[20], r.target)
    }
    @Test fun evenSplitTapAbstainsInsteadOfPickingArbitrarily() {
        // 5 windows of A vs 5 of B inside [5, 11]
        assertAbstain(AbstainReason.TAP_NOT_IN_CLUSTER, OfflineFootagePlanner.plan(windows(), 20f, tap = iv(5f, 11f)))
    }
    @Test fun malformedTapRejectedEvenWhenNoClusters() {
        val ws = windows().map { WindowEmbedding(it.startSec, it.endSec, null) }
        try { OfflineFootagePlanner.plan(ws, 20f, tap = iv(15f, 25f)); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) {}
    }
    private fun assertAbstain(reason: AbstainReason, r: PlanResult) {
        assertTrue("expected Abstain($reason) got $r", r is PlanResult.Abstain)
        assertEquals(reason, (r as PlanResult.Abstain).reason)
    }

    @Test fun unstablePlanAbstains() {
        // target group (angle 0), a "mid" group (angle 1.0, cos ~0.54: merges below mergeCos 0.54, splits above), and a distant other speaker
        val ws = ArrayList<WindowEmbedding>()
        var i = 0
        for ((ang, cnt) in listOf(0.0 to 10, 1.0 to 12, 3.0 to 10)) for (n in 0 until cnt) {
            ws.add(WindowEmbedding(i * 0.5f, i * 0.5f + 1.5f, vec(ang, (n % 3) * 0.03))); i++
        }
        val r = OfflineFootagePlanner.plan(ws, 17f, tap = iv(0f, 3f))
        assertAbstain(AbstainReason.PLAN_UNSTABLE, r)
    }
    @Test fun stablePlanStillPlans() {
        val r = OfflineFootagePlanner.plan(windows(), 20f, tap = iv(1f, 6f))
        assertTrue(r is PlanResult.Plan)
    }

    @Test fun planThatAbstainsUnderPerturbationIsUnstable() {
        // two groups at cos ~0.45: two clusters at mergeCos 0.5 (plans), one cluster at 0.4/0.45 (abstain flip). A flip must count as agreement 0.
        val ws = ArrayList<WindowEmbedding>()
        var i = 0
        for ((ang, cnt) in listOf(0.0 to 10, 1.1 to 12)) for (n in 0 until cnt) {
            ws.add(WindowEmbedding(i * 0.5f, i * 0.5f + 1.5f, vec(ang, (n % 3) * 0.03))); i++
        }
        val r = OfflineFootagePlanner.plan(ws, 12f, tap = iv(0f, 3f))
        assertAbstain(AbstainReason.PLAN_UNSTABLE, r)
    }
}
