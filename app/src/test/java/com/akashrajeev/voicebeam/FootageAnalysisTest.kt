package com.akashrajeev.voicebeam.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FootageAnalysisTest {
    private fun vec(angle: Double, jitter: Double) = floatArrayOf(cos(angle + jitter).toFloat(), sin(angle + jitter).toFloat(), 0.05f)

    /** 20s: speaker A 0-8 and 14-20, B 8-14. windows 1.5s hop 0.5s. */
    private fun windows(): List<WindowEmbedding> {
        val out = ArrayList<WindowEmbedding>()
        var t = 0f; var n = 0
        while (t + 1.5f <= 20f) {
            val mid = t + 0.75f
            val a = mid < 8f || mid >= 14f
            out.add(WindowEmbedding(t, t + 1.5f, vec(if (a) 0.0 else 2.0, (n % 3) * 0.03)))
            t += 0.5f; n++
        }
        return out
    }

    @Test fun clusterSeparatesTwoSpeakers() {
        val ws = windows(); val r = FootageAnalysis.cluster(ws)
        assertEquals(2, r.clusterCount)
        assertEquals(r.assign[0], r.assign[ws.size - 1])
        assertNotEquals(r.assign[0], r.assign[20])
    }

    @Test fun abstainedWindowsStayAbstained() {
        val ws = windows().toMutableList()
        ws[5] = WindowEmbedding(2.5f, 4f, null)
        val r = FootageAnalysis.cluster(ws)
        assertEquals(-1, r.assign[5])
    }

    @Test fun allAbstainedGivesNoClusters() {
        val ws = listOf(WindowEmbedding(0f, 1.5f, null))
        assertEquals(0, FootageAnalysis.cluster(ws).clusterCount)
    }

    @Test fun faceAssignmentNeedsMargin() {
        val ws = windows(); val r = FootageAnalysis.cluster(ws)
        val act = FootageAnalysis.binActivity(ws, r, 20f)
        val a = r.assign[0]
        val lip = FloatArray(40) { b -> if (act[a][b]) 0.8f else 0.1f }
        val asg = FootageAnalysis.assignFace(act, lip)
        assertNotNull(asg); assertEquals(a, asg!!.cluster)
        // flat lip series -> no assignment
        assertNull(FootageAnalysis.assignFace(act, FloatArray(40) { 0.5f }))
        // no face data -> no assignment
        assertNull(FootageAnalysis.assignFace(act, FloatArray(40) { Float.NaN }))
    }

    @Test fun autoReferenceStaysInsideTargetOnlyBins() {
        val ws = windows(); val r = FootageAnalysis.cluster(ws)
        val act = FootageAnalysis.binActivity(ws, r, 20f)
        val a = r.assign[0]
        val labels = FootageAnalysis.labelBins(act, a)
        val ref = FootageAnalysis.autoReference(labels, FootageAnalysis.binPurity(ws, r, 20f, a), null)
        assertNotNull(ref)
        val total = ref!!.sumOf { (it.endSec - it.startSec).toDouble() }
        assertTrue(total >= 3.0 && total <= 10.01)
        for (iv in ref) for (b in (iv.startSec / 0.5f).toInt() until (iv.endSec / 0.5f).toInt())
            assertEquals(FootageAnalysis.Seg.TARGET_ONLY, labels[b])
    }

    @Test fun autoReferenceNullWhenTooLittle() {
        val labels = Array(40) { FootageAnalysis.Seg.OVERLAP }
        assertNull(FootageAnalysis.autoReference(labels, FloatArray(40) { 1f }, null))
    }

    @Test fun doesNotForceMergeBelowThreshold() {
        // 3 orthogonal speakers, maxClusters=2: must NOT merge them; extra cluster abstains instead
        val ws = ArrayList<WindowEmbedding>()
        for (s in 0 until 3) for (n in 0 until 5) {
            val e = FloatArray(3); e[s] = 1f; ws.add(WindowEmbedding(ws.size * 0.5f, ws.size * 0.5f + 1.5f, e))
        }
        val r = FootageAnalysis.cluster(ws, mergeCos = 0.55f, maxClusters = 2)
        assertEquals(2, r.clusterCount)
        for (s in 0 until 3) assertTrue((0 until 5).map { r.assign[s * 5 + it] }.distinct().size == 1)
        val a0 = r.assign[0]; val a1 = r.assign[5]; val a2 = r.assign[10]
        assertTrue(listOf(a0, a1, a2).count { it == -1 } == 1)
        assertTrue(listOf(a0, a1, a2).filter { it >= 0 }.distinct().size == 2)
    }

    @Test fun tinyClustersAllAbstain() {
        val ws = listOf(
            WindowEmbedding(0f, 1.5f, floatArrayOf(1f, 0f, 0f)),
            WindowEmbedding(0.5f, 2f, floatArrayOf(0f, 1f, 0f)))
        val r = FootageAnalysis.cluster(ws)
        assertEquals(0, r.clusterCount)
        assertTrue(r.assign.all { it == -1 })
    }

    @Test fun nonFiniteEmbeddingAbstains() {
        val ws = windows().toMutableList()
        ws[3] = WindowEmbedding(1.5f, 3f, floatArrayOf(Float.NaN, 0f, 0f))
        assertEquals(-1, FootageAnalysis.cluster(ws).assign[3])
    }

    @Test(expected = IllegalArgumentException::class) fun mixedDimensionsRejected() {
        val ws = windows().toMutableList()
        ws[3] = WindowEmbedding(1.5f, 3f, floatArrayOf(1f, 0f))
        FootageAnalysis.cluster(ws)
    }

    @Test(expected = IllegalArgumentException::class) fun cosineDimensionMismatchThrows() {
        FootageAnalysis.cosine(floatArrayOf(1f), floatArrayOf(1f, 0f))
    }

    @Test(expected = IllegalArgumentException::class) fun badDurationRejected() {
        val ws = windows(); FootageAnalysis.binActivity(ws, FootageAnalysis.cluster(ws), Float.NaN)
    }

    @Test(expected = IllegalArgumentException::class) fun targetOutOfRangeRejected() {
        val ws = windows(); val r = FootageAnalysis.cluster(ws)
        FootageAnalysis.labelBins(FootageAnalysis.binActivity(ws, r, 20f), 9)
    }

    @Test fun binPurityIsTargetSpecific() {
        val ws = windows(); val r = FootageAnalysis.cluster(ws)
        val a = r.assign[0]; val b = r.assign[20]
        val pa = FootageAnalysis.binPurity(ws, r, 20f, a)
        // bins well inside speaker B's 8-14s turn have no target-A confidence
        assertEquals(-1f, pa[(10f / 0.5f).toInt()], 0f)
        val pb = FootageAnalysis.binPurity(ws, r, 20f, b)
        assertTrue(pb[(10f / 0.5f).toInt()] > 0f)
    }

    @Test(expected = IllegalArgumentException::class) fun autoReferenceLengthMismatch() {
        FootageAnalysis.autoReference(Array(10) { FootageAnalysis.Seg.TARGET_ONLY }, FloatArray(9) { 1f }, null)
    }

    @Test(expected = IllegalArgumentException::class) fun badWindowRejected() { WindowEmbedding(2f, 1f, null) }
    @Test(expected = IllegalArgumentException::class) fun nanWindowRejected() { WindowEmbedding(Float.NaN, 1f, null) }
    @Test(expected = IllegalArgumentException::class) fun clusterResultIndexOutOfRange() { ClusterResult(intArrayOf(0, 2), 2, FloatArray(2)) }
    @Test(expected = IllegalArgumentException::class) fun clusterResultSizeMismatch() { ClusterResult(intArrayOf(0), 1, FloatArray(2)) }
    @Test(expected = IllegalArgumentException::class) fun resultWindowCountMismatch() {
        val ws = windows(); FootageAnalysis.binActivity(ws.drop(1), FootageAnalysis.cluster(ws), 20f)
    }
    @Test(expected = IllegalArgumentException::class) fun raggedActivityRejected() {
        FootageAnalysis.labelBins(arrayOf(BooleanArray(4), BooleanArray(3)), 0)
    }
    @Test(expected = IllegalArgumentException::class) fun badClusterParamsRejected() { FootageAnalysis.cluster(windows(), mergeCos = 2f) }
    @Test(expected = IllegalArgumentException::class) fun zeroMaxClustersRejected() { FootageAnalysis.cluster(windows(), maxClusters = 0) }
    @Test(expected = IllegalArgumentException::class) fun lipLengthMismatchRejected() {
        val ws = windows(); val act = FootageAnalysis.binActivity(ws, FootageAnalysis.cluster(ws), 20f)
        FootageAnalysis.assignFace(act, FloatArray(39) { 0.5f })
    }
    @Test fun infiniteLipTreatedAsMissing() {
        val ws = windows(); val r = FootageAnalysis.cluster(ws)
        val act = FootageAnalysis.binActivity(ws, r, 20f)
        assertNull(FootageAnalysis.assignFace(act, FloatArray(40) { Float.POSITIVE_INFINITY }))
    }
}
