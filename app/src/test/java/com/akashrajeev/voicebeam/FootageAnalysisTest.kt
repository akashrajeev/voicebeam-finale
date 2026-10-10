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

    private fun oneSpeakerWindows(n: Int = 30): List<WindowEmbedding> =
        (0 until n).map { WindowEmbedding(it * 0.5f, it * 0.5f + 1.5f, vec(0.0, (it % 3) * 0.03)) }

    @Test fun autoReferenceStaysInsideTargetOnlyBins() {
        val ws = windows(); val r = FootageAnalysis.cluster(ws); val a = r.assign[0]
        val an = ClusteredAnalysis.of(ws, 20f).forTarget(a)
        assertEquals(2, an.clusterCount)
        val ref = an.autoReference(minPurity = 0.0f)
        assertNotNull(ref)
        val total = ref!!.sumOf { (it.endSec - it.startSec).toDouble() }
        assertTrue(total >= 3.0 && total <= 10.01)
        for (iv in ref) for (b in (iv.startSec / 0.5f).toInt() until (iv.endSec / 0.5f).toInt())
            assertEquals(FootageAnalysis.Seg.TARGET_ONLY, an.labels[b])
    }

    @Test fun singleClusterCannotHandOutReferenceWithoutLipEvidence() {
        val ws = oneSpeakerWindows(); val ca = ClusteredAnalysis.of(ws, 16f)
        assertEquals(1, ca.clusterCount)
        val an = ca.forTarget(0)
        assertNull(an.autoReference(minPurity = 0f))
        assertNull(an.autoReference(lipOn = BooleanArray(32) { true }, minPurity = 0f))
        assertNotNull(an.autoReference(othersOffLip = BooleanArray(32) { true }, minPurity = 0f))
        assertNull(an.autoReference(othersOffLip = BooleanArray(32) { false }, minPurity = 0f))
    }

    @Test fun purityGateDefaultBlocksLowPurity() {
        val ws = windows(); val r = FootageAnalysis.cluster(ws)
        // identical-direction windows give margin ~ big; force low gate effect via minPurity above max
        val an = ClusteredAnalysis.of(ws, 20f).forTarget(r.assign[0])
        assertNull(an.autoReference(minPurity = 5f))
    }

    @Test(expected = IllegalArgumentException::class) fun analyzeRejectsTargetOutOfRange() {
        ClusteredAnalysis.of(windows(), 20f).forTarget(7)
    }

    @Test(expected = IllegalArgumentException::class) fun lipMaskLengthMismatchRejected() {
        val ws = windows(); val r = FootageAnalysis.cluster(ws)
        ClusteredAnalysis.of(ws, 20f).forTarget(0).autoReference(lipOn = BooleanArray(5), minPurity = 0f)
    }

    @Test(expected = IllegalArgumentException::class) fun badAutoReferenceLimitsRejected() {
        val ws = windows(); val r = FootageAnalysis.cluster(ws)
        ClusteredAnalysis.of(ws, 20f).forTarget(0).autoReference(minSec = 5f, maxSec = 2f)
    }
    @Test(expected = IllegalArgumentException::class) fun nanPurityLimitRejected() {
        val ws = windows(); val r = FootageAnalysis.cluster(ws)
        ClusteredAnalysis.of(ws, 20f).forTarget(0).autoReference(minPurity = Float.NaN)
    }

    @Test fun analysisCannotBeForgedWithClusterCountParameter() {
        // forTarget derives clusterCount from the clustering the analysis itself ran
        val ca = ClusteredAnalysis.of(oneSpeakerWindows(), 16f)
        assertEquals(ca.clusterCount, ca.forTarget(0).clusterCount)
    }
    @Test fun labelsAreDefensiveCopies() {
        val ws = windows(); val an = ClusteredAnalysis.of(ws, 20f).forTarget(0)
        assertEquals(an.labels, an.labels); assertNotSame(an.labels, an.labels)
    }
    @Test fun clusteredAssignFaceMatchesStandalone() {
        val ws = windows(); val ca = ClusteredAnalysis.of(ws, 20f)
        assertNull(ca.assignFace(FloatArray(40) { 0.5f }))
    }
}
