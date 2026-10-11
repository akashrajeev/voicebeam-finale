package com.akashrajeev.voicebeam.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The planner re-attaches an abstained short utterance only when a rawEmbed is supplied, and only into a currently-N bin. */
class AbstainAttachPlannerTest {
    private fun vec(angle: Double, jitter: Double) = floatArrayOf(cos(angle + jitter).toFloat(), sin(angle + jitter).toFloat(), 0.05f)

    /** Windows every 0.5 s over 20 s. A (angle 0) from 4 s on except 8-14 which is B (angle 2). Windows starting before 1.5 s are A-like but denoised-distorted (orthogonal): they abstain. */
    private fun windows(): List<WindowEmbedding> {
        val out = ArrayList<WindowEmbedding>(); var t = 0f; var n = 0
        while (t + 1.5f <= 20f) {
            val mid = t + 0.75f
            val emb = when {
                t < 1.0f -> vec(-1.6, 0.0)          // distorted intro windows: far from both clusters
                t < 4f -> null                      // silence / not eligible
                mid in 8f..14f -> vec(2.0, (n % 3) * 0.03)
                else -> vec(0.0, (n % 3) * 0.03)
            }
            out.add(WindowEmbedding(t, t + 1.5f, emb)); t += 0.5f; n++
        }
        return out
    }
    private val tap = FootageAnalysis.Interval(4.5f, 8.5f)
    // raw view: the intro windows look like A, everything else as the clusters
    private fun raw(ws: List<WindowEmbedding>): (Int) -> FloatArray? = { i ->
        val w = ws[i]
        when {
            w.startSec < 1.0f -> vec(0.1, 0.0)
            w.emb == null -> null
            else -> w.emb
        }
    }

    @Test fun withoutRawEmbedTheIntroStaysNone() {
        val ws = windows()
        val p = OfflineFootagePlanner.plan(ws, 20f, tap = tap) as PlanResult.Plan
        assertEquals(FootageAnalysis.Seg.NONE, p.labels[1])
    }
    @Test fun withRawEmbedTheIntroBecomesTarget() {
        val ws = windows()
        val base = OfflineFootagePlanner.plan(ws, 20f, tap = tap) as PlanResult.Plan
        val p = OfflineFootagePlanner.plan(ws, 20f, tap = tap, rawEmbed = raw(ws)) as PlanResult.Plan
        assertEquals(FootageAnalysis.Seg.TARGET_ONLY, p.labels[1])
        // only NONE bins may change, and only to TARGET_ONLY
        for (b in base.labels.indices) if (base.labels[b] != p.labels[b]) {
            assertEquals(FootageAnalysis.Seg.NONE, base.labels[b]); assertEquals(FootageAnalysis.Seg.TARGET_ONLY, p.labels[b])
        }
        assertEquals(base.target, p.target); assertEquals(base.clusterCount, p.clusterCount)
    }
    @Test fun rawEmbedThatPointsAtOtherChangesNothing() {
        val ws = windows()
        val base = OfflineFootagePlanner.plan(ws, 20f, tap = tap) as PlanResult.Plan
        val p = OfflineFootagePlanner.plan(ws, 20f, tap = tap, rawEmbed = { i -> if (ws[i].startSec < 1.0f) vec(2.0, 0.0) else ws[i].emb }) as PlanResult.Plan
        assertEquals(base.labels, p.labels)
    }
    @Test fun rawEmbedFailingEverywhereChangesNothing() {
        val ws = windows()
        val base = OfflineFootagePlanner.plan(ws, 20f, tap = tap) as PlanResult.Plan
        val p = OfflineFootagePlanner.plan(ws, 20f, tap = tap, rawEmbed = { null }) as PlanResult.Plan
        assertEquals(base.labels, p.labels)
    }

    @Test fun tapSpanningTwoClustersAbstainsWithMeasuredDetail() {
        val ws = windows()
        val r = OfflineFootagePlanner.plan(ws, 20f, tap = FootageAnalysis.Interval(6f, 10f))
        assertTrue(r is PlanResult.Abstain)
        r as PlanResult.Abstain
        assertEquals(AbstainReason.TAP_NOT_IN_CLUSTER, r.reason)
        assertTrue(r.detail, r.detail.contains("check=margin"))
        assertTrue(r.detail, r.detail.contains("runner-up="))
        assertTrue(r.detail, r.detail.contains("clusters=2"))
    }
}
