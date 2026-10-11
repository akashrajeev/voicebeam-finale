package com.akashrajeev.voicebeam.core

import kotlin.math.sqrt

/**
 * Re-labels windows that were embedded but ended up in no cluster (clusters under
 * minClusterWindows abstain) as the target's, when RAW-audio embeddings say so.
 *
 * Why: a short utterance (under about 2.5 s) can never fill a 3-window cluster, so it is
 * abstained, binned N, and HARD_MUTE silences real target speech. With denoised embeddings
 * the short onset is also distorted (observed: cosine to the target 0.66/0.70 raw vs 0.40/0.54 denoised).
 *
 * Strict rule, PROVISIONAL (n=8 abstained windows over 3 fixture clips plus one proxy clip;
 * margins are thin and no abstained window in that corpus was truly "other", so leakage on
 * near-identical voices is untested): attach only if raw cosine to the target's raw centroid
 * is at least [MIN_COS] AND beats the best other cluster's raw centroid by at least [MIN_MARGIN].
 * Anything else stays abstained (N, muted) = the behaviour before this rule.
 * It only ever turns N windows into target windows, never touches clustered windows.
 * Worst case is a short (about 1.5 s) leak into the target; the status quo is silenced target speech.
 *
 * Pure: the raw embedding is supplied by [decide]'s `raw` callback, called with a WINDOW INDEX
 * (null = cannot embed). Cost is bounded: up to [MAX_CENTROID_WINDOWS] windows per cluster for
 * the raw centroids plus up to [MAX_CANDIDATES] abstained windows.
 */
object AbstainAttach {
    const val MIN_COS = 0.60f
    const val MIN_MARGIN = 0.15f
    const val MAX_CENTROID_WINDOWS = 8
    const val MAX_CANDIDATES = 40

    class Attach(val index: Int, val cos: Float, val margin: Float)

    fun decide(
        embedded: List<Boolean>, assign: IntArray, clusterCount: Int, target: Int,
        raw: (Int) -> FloatArray?,
        minCos: Float = MIN_COS, minMargin: Float = MIN_MARGIN
    ): List<Attach> {
        require(embedded.size == assign.size) { "embedded/assign size mismatch" }
        require(target in 0 until clusterCount) { "target $target outside $clusterCount clusters" }
        require(minCos.isFinite() && minMargin.isFinite()) { "bad attach thresholds" }
        if (clusterCount < 2) return emptyList()
        val candidates = assign.indices.filter { assign[it] == -1 && embedded[it] }.take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return emptyList()

        val centroids = arrayOfNulls<FloatArray>(clusterCount)
        for (c in 0 until clusterCount) {
            val members = assign.indices.filter { assign[it] == c }
            val picks = if (members.size <= MAX_CENTROID_WINDOWS) members else List(MAX_CENTROID_WINDOWS) { members[it * members.size / MAX_CENTROID_WINDOWS] }
            val vecs = picks.mapNotNull { unit(raw(it)) }
            if (vecs.size < 2) continue
            val sum = FloatArray(vecs[0].size)
            for (v in vecs) { if (v.size != sum.size) return emptyList(); for (j in sum.indices) sum[j] += v[j] }
            centroids[c] = unit(sum)
        }
        val targetCentroid = centroids[target] ?: return emptyList()
        // Every other cluster needs a centroid, else the margin could be overstated.
        val others = ArrayList<FloatArray>()
        for (c in 0 until clusterCount) if (c != target) others.add(centroids[c] ?: return emptyList())

        val out = ArrayList<Attach>()
        for (i in candidates) {
            val v = unit(raw(i)) ?: continue
            if (v.size != targetCentroid.size) continue
            val ct = dot(v, targetCentroid)
            var co = -1f
            for (o in others) co = maxOf(co, dot(v, o))
            val margin = ct - co
            if (ct >= minCos && margin >= minMargin) out.add(Attach(i, ct, margin))
        }
        return out
    }

    private fun dot(a: FloatArray, b: FloatArray): Float { var d = 0f; for (i in a.indices) d += a[i] * b[i]; return d }

    private fun unit(v: FloatArray?): FloatArray? {
        if (v == null || v.isEmpty()) return null
        var n = 0f
        for (x in v) { if (!x.isFinite()) return null; n += x * x }
        if (n <= 1e-12f) return null
        val s = 1f / sqrt(n)
        return FloatArray(v.size) { v[it] * s }
    }
}
