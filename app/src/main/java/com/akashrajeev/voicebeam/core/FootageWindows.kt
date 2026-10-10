package com.akashrajeev.voicebeam.core

/** Windowing + lip-series helpers. Pure Kotlin. The embed function is injected (production: VoicePrint.embed), nullable = abstain. */
object FootageWindows {
    const val WIN_SEC = 1.5f
    const val HOP_SEC = 0.5f

    /**
     * Slides full WIN_SEC windows over 16 kHz mono samples. A null from [embed] is preserved as an abstained window (never a zero vector).
     * Audio shorter than one window yields an empty list. [isCancelled] is polled per window; when it returns true the result so far is discarded (null).
     */
    fun embedWindows(
        samples: FloatArray, sampleRate: Int = 16000,
        embed: (FloatArray) -> FloatArray?,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<WindowEmbedding>? {
        val win = (WIN_SEC * sampleRate).toInt(); val hop = (HOP_SEC * sampleRate).toInt()
        require(sampleRate >= 8000 && win > 0 && hop > 0) { "bad sample rate $sampleRate" }
        if (samples.size < win) return emptyList()
        val total = (samples.size - win) / hop + 1
        val out = ArrayList<WindowEmbedding>(total)
        for (n in 0 until total) {
            if (isCancelled()) return null
            val a = n * hop
            val e = embed(samples.copyOfRange(a, a + win))
            if (isCancelled()) return null // recheck after a slow embed returns
            out.add(WindowEmbedding(a.toFloat() / sampleRate, (a + win).toFloat() / sampleRate, e))
            onProgress(n + 1, total)
        }
        return out
    }

    /**
     * Timestamped lip activity (one value per analysed video frame, any rate, need not be sorted) -> mean per 0.5s bin.
     * Bins with no finite sample are NaN (= no face data). Non-finite values and timestamps outside [0, durationSec) are ignored.
     */
    fun binLip(timesSec: FloatArray, values: FloatArray, durationSec: Float): FloatArray {
        require(timesSec.size == values.size) { "times/values length mismatch" }
        require(durationSec.isFinite() && durationSec > 0f) { "bad duration" }
        val bins = Math.ceil((durationSec / FootageAnalysis.BIN_SEC).toDouble()).toInt()
        val sum = FloatArray(bins); val cnt = IntArray(bins)
        for (i in timesSec.indices) {
            val t = timesSec[i]; val v = values[i]
            if (!t.isFinite() || !v.isFinite() || t < 0f || t >= durationSec) continue
            val b = minOf(bins - 1, (t / FootageAnalysis.BIN_SEC).toInt())
            sum[b] += v; cnt[b]++
        }
        return FloatArray(bins) { if (cnt[it] == 0) Float.NaN else sum[it] / cnt[it] }
    }

    /** Lips-on per bin; NaN (no data) is never "on". */
    fun lipOn(binned: FloatArray, onThreshold: Float): BooleanArray {
        require(onThreshold.isFinite()) { "bad threshold" }
        return BooleanArray(binned.size) { binned[it].isFinite() && binned[it] >= onThreshold }
    }

    /** Others-off evidence: true only where the OTHER tracked faces all have DATA and are below offThreshold. NaN is unknown, not off. */
    fun othersOff(otherBinned: List<FloatArray>, offThreshold: Float): BooleanArray? {
        require(offThreshold.isFinite()) { "bad threshold" }
        if (otherBinned.isEmpty()) return null
        val n = otherBinned[0].size
        require(otherBinned.all { it.size == n }) { "series length mismatch" }
        return BooleanArray(n) { b -> otherBinned.all { it[b].isFinite() && it[b] < offThreshold } }
    }
}
