package com.akashrajeev.voicebeam.core

import kotlin.math.sqrt

/** Pure analysis for whole-file speaker clustering. No ORT, no Android. Null embedding = abstained, never a zero vector. */
class WindowEmbedding(val startSec: Float, val endSec: Float, val emb: FloatArray?) {
    init { require(startSec.isFinite() && endSec.isFinite() && startSec >= 0f && endSec > startSec) { "bad window [$startSec, $endSec)" } }
}

class ClusterResult internal constructor(val assign: IntArray, val clusterCount: Int, val purity: FloatArray) {
    init {
        require(clusterCount >= 0 && assign.size == purity.size) { "assign/purity size mismatch" }
        require(assign.all { it >= -1 && it < clusterCount }) { "cluster index out of range" }
    }
}

object FootageAnalysis {
    const val BIN_SEC = 0.5f

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "embedding dimension mismatch: ${a.size} vs ${b.size}" }
        var d = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val den = sqrt(na) * sqrt(nb)
        return if (den < 1e-9f) 0f else d / den
    }

    private fun finite(e: FloatArray?) = e != null && e.isNotEmpty() && e.all { it.isFinite() }

    private fun bins(durationSec: Float): Int {
        require(durationSec.isFinite() && durationSec > 0f) { "bad duration $durationSec" }
        return Math.ceil((durationSec / BIN_SEC).toDouble()).toInt()
    }

    private fun centroid(ws: List<WindowEmbedding>, idx: List<Int>): FloatArray {
        val c = FloatArray(ws[idx[0]].emb!!.size)
        for (i in idx) { val e = ws[i].emb!!; for (j in c.indices) c[j] += e[j] }
        return c
    }

    /**
     * Average-link agglomerative clustering on cosine. Merging stops as soon as the best average cosine < mergeCos (never forced).
     * Windows with null/non-finite embeddings abstain (-1). Clusters smaller than minClusterWindows abstain; if none qualify, nothing is clustered.
     * If more than maxClusters qualify, only the largest maxClusters are kept and the rest abstain.
     */
    internal fun cluster(ws: List<WindowEmbedding>, mergeCos: Float = 0.5f, maxClusters: Int = 6, minClusterWindows: Int = 3): ClusterResult {
        require(mergeCos.isFinite() && mergeCos in -1f..1f && maxClusters >= 1 && minClusterWindows >= 1) { "bad cluster parameters" }
        val valid = ws.indices.filter { finite(ws[it].emb) }
        val dim = valid.firstOrNull()?.let { ws[it].emb!!.size }
        require(valid.all { ws[it].emb!!.size == dim }) { "mixed embedding dimensions" }
        val assign = IntArray(ws.size) { -1 }
        val purity = FloatArray(ws.size)
        if (valid.isEmpty()) return ClusterResult(assign, 0, purity)
        val groups = valid.map { mutableListOf(it) }.toMutableList()
        while (groups.size > 1) {
            var best = -2f; var bi = -1; var bj = -1
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                var sum = 0f
                for (x in groups[i]) for (y in groups[j]) sum += cosine(ws[x].emb!!, ws[y].emb!!)
                val avg = sum / (groups[i].size * groups[j].size)
                if (avg > best) { best = avg; bi = i; bj = j }
            }
            if (best < mergeCos) break
            groups[bi].addAll(groups[bj]); groups.removeAt(bj)
        }
        val keep = groups.filter { it.size >= minClusterWindows }.sortedByDescending { it.size }.take(maxClusters)
        if (keep.isEmpty()) return ClusterResult(assign, 0, purity)
        val cents = keep.map { centroid(ws, it) }
        keep.forEachIndexed { k, g -> for (i in g) assign[i] = k }
        for (i in valid) {
            if (assign[i] < 0) continue
            val own = cosine(ws[i].emb!!, cents[assign[i]])
            var other = -1f
            for (k in cents.indices) if (k != assign[i]) other = maxOf(other, cosine(ws[i].emb!!, cents[k]))
            purity[i] = if (cents.size == 1) own else own - other
        }
        return ClusterResult(assign, keep.size, purity)
    }

    /** Per-0.5s-bin cluster activity. bin b covers [b*0.5, (b+1)*0.5). A window votes for bins whose centre it covers. */
    fun binActivity(ws: List<WindowEmbedding>, res: ClusterResult, durationSec: Float): Array<BooleanArray> {
        require(res.assign.size == ws.size) { "result/window count mismatch" }
        val bins = bins(durationSec)
        val act = Array(res.clusterCount) { BooleanArray(bins) }
        for (i in ws.indices) {
            val c = res.assign[i]; if (c < 0) continue
            for (b in 0 until bins) {
                val mid = (b + 0.5f) * BIN_SEC
                if (mid >= ws[i].startSec && mid < ws[i].endSec) act[c][b] = true
            }
        }
        return act
    }

    enum class Seg { TARGET_ONLY, OTHER_ONLY, OVERLAP, NONE }

    fun labelBins(act: Array<BooleanArray>, target: Int): Array<Seg> {
        require(target in act.indices) { "target $target outside ${act.size} clusters" }
        val bins = act[0].size
        require(act.all { it.size == bins }) { "activity rows differ in length" }
        return Array(bins) { b ->
            val t = act[target][b]
            val o = act.indices.any { it != target && act[it][b] }
            when { t && o -> Seg.OVERLAP; t -> Seg.TARGET_ONLY; o -> Seg.OTHER_ONLY; else -> Seg.NONE }
        }
    }

    class Assignment(val cluster: Int, val corr: Float, val margin: Float)

    /** Correlate each cluster's activity with the chosen face's lip series (NaN = no face data). Null unless corr >= minCorr and margin over runner-up >= minMargin. */
    fun assignFace(act: Array<BooleanArray>, lip: FloatArray, minCorr: Float = 0.25f, minMargin: Float = 0.15f, minBins: Int = 20): Assignment? {
        require(minBins >= 2 && minCorr.isFinite() && minMargin.isFinite() && minMargin >= 0f) { "bad assign parameters" }
        if (act.isEmpty()) return null
        require(act.all { it.size == lip.size }) { "lip series length ${lip.size} != activity length" }
        val corrs = FloatArray(act.size) { c ->
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
            for (b in lip.indices) if (lip[b].isFinite()) { xs.add(if (act[c][b]) 1f else 0f); ys.add(lip[b]) }
            if (xs.size < minBins) Float.NaN else pearson(xs, ys)
        }
        val order = corrs.indices.filter { !corrs[it].isNaN() }.sortedByDescending { corrs[it] }
        if (order.isEmpty()) return null
        val top = order[0]; val second = if (order.size > 1) corrs[order[1]] else 0f
        val margin = corrs[top] - second
        return if (corrs[top] >= minCorr && margin >= minMargin) Assignment(top, corrs[top], margin) else null
    }

    fun pearson(x: List<Float>, y: List<Float>): Float {
        val n = x.size; val mx = x.sum() / n; val my = y.sum() / n
        var sxy = 0f; var sxx = 0f; var syy = 0f
        for (i in 0 until n) { val dx = x[i] - mx; val dy = y[i] - my; sxy += dx * dy; sxx += dx * dx; syy += dy * dy }
        val den = sqrt(sxx * syy)
        return if (den < 1e-9f) 0f else sxy / den
    }

    class Interval(val startSec: Float, val endSec: Float)

    /**
     * Auto-reference: bins where target is the ONLY active cluster, window purity >= minPurity, and (when lip data exist)
     * target lips on / others unknown-or-off. Returns up to maxSec of the most pure contiguous runs; null if under minSec.
     */
    internal fun autoReferenceInternal(
        labels: Array<Seg>, binPurity: FloatArray, clusterCount: Int, lipOn: BooleanArray? = null,
        othersOffLip: BooleanArray? = null,
        minPurity: Float = 0.30f, minSec: Float = 3f, maxSec: Float = 10f, minRunSec: Float = 1f
    ): List<Interval>? {
        require(clusterCount >= 1) { "no clusters: nothing to reference" }
        require(minPurity.isFinite() && minSec.isFinite() && maxSec.isFinite() && minRunSec.isFinite() &&
            minSec > 0f && maxSec >= minSec && minRunSec > 0f) { "bad autoReference limits" }
        // A single cluster is no evidence that other speakers are off (calibration: 76-82% contaminated refs). Abstain unless lips confirm.
        if (clusterCount < 2 && othersOffLip == null) return null
        require(othersOffLip == null || othersOffLip.size == labels.size) { "series length mismatch" }
        require(binPurity.size == labels.size && (lipOn == null || lipOn.size == labels.size)) { "series length mismatch" }
        val ok = BooleanArray(labels.size) { b ->
            labels[b] == Seg.TARGET_ONLY && binPurity[b] >= minPurity && (lipOn == null || lipOn[b]) && (othersOffLip == null || othersOffLip[b])
        }
        data class Run(val a: Int, val b: Int, val score: Float)
        val runs = ArrayList<Run>()
        var b = 0
        while (b < ok.size) {
            if (!ok[b]) { b++; continue }
            var e = b; var s = 0f
            while (e < ok.size && ok[e]) { s += binPurity[e]; e++ }
            if ((e - b) * BIN_SEC >= minRunSec) runs.add(Run(b, e, s / (e - b)))
            b = e
        }
        runs.sortByDescending { it.score }
        val out = ArrayList<Interval>(); var total = 0f
        for (r in runs) {
            val len = (r.b - r.a) * BIN_SEC
            val take = minOf(len, maxSec - total)
            if (take <= 0f) break
            out.add(Interval(r.a * BIN_SEC, r.a * BIN_SEC + take)); total += take
        }
        return if (total >= minSec) out.sortedBy { it.startSec } else null
    }

    /** Per-bin purity of the TARGET cluster only (-1 where the target has no window covering the bin centre). */
    fun binPurity(ws: List<WindowEmbedding>, res: ClusterResult, durationSec: Float, target: Int): FloatArray {
        require(target in 0 until res.clusterCount) { "target $target outside ${res.clusterCount} clusters" }
        require(res.assign.size == ws.size) { "result/window count mismatch" }
        val p = FloatArray(bins(durationSec)) { -1f }
        for (i in ws.indices) {
            if (res.assign[i] != target) continue
            for (b in p.indices) {
                val mid = (b + 0.5f) * BIN_SEC
                if (mid >= ws[i].startSec && mid < ws[i].endSec) p[b] = maxOf(p[b], res.purity[i])
            }
        }
        return p
    }

}

/**
 * The ONLY way to obtain an auto-reference. Clustering runs inside [ClusteredAnalysis.of]; the constructor is private and
 * TargetAnalysis derives clusterCount from its parent, so no caller can supply a forged cluster count or ClusterResult.
 * A single-cluster (or zero-cluster) analysis cannot hand out a reference unless explicit others-off lip evidence is passed.
 */
class ClusteredAnalysis private constructor(
    internal val windows: List<WindowEmbedding>, internal val result: ClusterResult, internal val durationSec: Float
) {
    val clusterCount: Int get() = result.clusterCount
    private val activity: Array<BooleanArray> = FootageAnalysis.binActivity(windows, result, durationSec)

    fun assignFace(lip: FloatArray, minCorr: Float = 0.25f, minMargin: Float = 0.15f, minBins: Int = 20) =
        FootageAnalysis.assignFace(activity, lip, minCorr, minMargin, minBins)

    /** Cluster index per window (-1 = abstained). Read-only copy. */
    fun windowClusters(): List<Int> = result.assign.toList()

    internal fun activityCopy(): Array<BooleanArray> = Array(activity.size) { activity[it].copyOf() }

    fun forTarget(target: Int): TargetAnalysis = TargetAnalysis(this, target)

    companion object {
        fun of(ws: List<WindowEmbedding>, durationSec: Float, mergeCos: Float = 0.5f, maxClusters: Int = 6, minClusterWindows: Int = 3) =
            ClusteredAnalysis(ws, FootageAnalysis.cluster(ws, mergeCos, maxClusters, minClusterWindows), durationSec)
    }
}

class TargetAnalysis internal constructor(parent: ClusteredAnalysis, val target: Int) {
    val clusterCount: Int = parent.clusterCount
    private val segs: Array<FootageAnalysis.Seg>
    private val pur: FloatArray
    init {
        require(target in 0 until clusterCount) { "target $target outside $clusterCount clusters" }
        segs = FootageAnalysis.labelBins(parent.activityCopy(), target)
        pur = FootageAnalysis.binPurity(parent.windows, parent.result, parent.durationSec, target)
    }
    val labels: List<FootageAnalysis.Seg> get() = segs.toList()
    val purity: List<Float> get() = pur.toList()

    fun autoReference(
        lipOn: BooleanArray? = null, othersOffLip: BooleanArray? = null,
        minPurity: Float = 0.30f, minSec: Float = 3f, maxSec: Float = 10f, minRunSec: Float = 1f
    ): List<FootageAnalysis.Interval>? =
        FootageAnalysis.autoReferenceInternal(segs, pur, clusterCount, lipOn, othersOffLip, minPurity, minSec, maxSec, minRunSec)
}
