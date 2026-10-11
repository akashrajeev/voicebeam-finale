package com.akashrajeev.voicebeam.core

import kotlin.math.hypot

/**
 * Offline face tracks -> per-0.5 s lip series for TapProposal. Feed analysed video frames IN VIDEO-TIME ORDER (timestamps = the video's PTS in ms, not a wall clock),
 * ideally at 8 fps or more (LipActivity needs 4 samples inside its 700 ms window; a slower rate reads as "mouth still"). Pure JVM: no Android types.
 *
 * Uses the live lane's FaceTracker (proximity IDs, NOT biometric: a face that leaves and re-enters gets a new ID, so its earlier bins simply stay NaN = unknown,
 * which is conservative) and LipActivity. A track's first [WARMUP_MS] are discarded: LipActivity reads 0 until its window fills, which must never look like "silent".
 */
class FaceTrackBinner(private val durationSec: Float) {
    companion object {
        const val WARMUP_MS = 700L
        const val LIP_ON = 0.5f
        const val OTHERS_OFF = 0.3f
        const val ALIAS_MIN_LIP_BINS = 3
        const val ALIAS_TARGET_SHARE = 0.7f
        const val ALIAS_OTHER_MAX = 0.2f
    }
    private class Sample(val id: Int, val tMs: Long, val box: Box, val lip: Float)
    private val tracker = FaceTracker()
    private val firstSeen = HashMap<Int, Long>()
    private val samples = ArrayList<Sample>()
    private var lastMs = Long.MIN_VALUE

    init { require(durationSec.isFinite() && durationSec > 0f) { "bad duration" } }

    fun add(timeMs: Long, faces: List<FaceObservation>) {
        require(timeMs >= 0 && timeMs >= lastMs) { "frames must arrive in video-time order" }
        lastMs = timeMs
        val seen = tracker.update(timeMs, faces).filter { it.lastSeenMs == timeMs }
        for (t in seen) {
            val first = firstSeen.getOrPut(t.id) { timeMs }
            samples.add(Sample(t.id, timeMs, t.box, if (timeMs - first >= WARMUP_MS) t.speaking else Float.NaN))
        }
    }

    /** Track ids seen so far. */
    fun trackIds(): List<Int> = samples.map { it.id }.distinct().sorted()

    /** The track the user tapped at [timeSec] (normalised x, y in the upright frame), or null. Looks at samples within 0.5 s of the time. */
    fun idAt(nx: Float, ny: Float, timeSec: Float): Int? {
        val t = (timeSec * 1000f).toLong()
        val near = samples.filter { kotlin.math.abs(it.tMs - t) <= 500 }
        val hit = near.filter { it.box.contains(nx, ny, pad = 0.04f) }.minByOrNull { kotlin.math.abs(it.tMs - t) }
        if (hit != null) return hit.id
        val c = near.minByOrNull { hypot(it.box.cx - nx, it.box.cy - ny) } ?: return null
        return if (hypot(c.box.cx - nx, c.box.cy - ny) < 0.25f) c.id else null
    }

    /** Tracked faces near [timeSec] (latest sample per track within 0.5 s), for drawing tappable boxes. Same-frame data only; not a claim the face is talking. */
    fun facesAt(timeSec: Float): List<Pair<Int, Box>> {
        val t = (timeSec * 1000f).toLong()
        return samples.filter { kotlin.math.abs(it.tMs - t) <= 500 }
            .groupBy { it.id }
            .map { (id, l) -> id to l.minByOrNull { kotlin.math.abs(it.tMs - t) }!!.box }
            .sortedBy { it.first }
    }

    /** Video time of the first analysed face sample, or null if no face was ever tracked. */
    fun firstFaceSec(): Float? = samples.firstOrNull()?.let { it.tMs / 1000f }

    /** Per 0.5 s bin: true when the tapped track has at least one sample in the bin (face tracked, talking or not). Unknown/lost = false. */
    fun visible(targetId: Int): BooleanArray {
        require(samples.any { it.id == targetId }) { "unknown face track" }
        val bins = Math.ceil((durationSec / FootageAnalysis.BIN_SEC).toDouble()).toInt()
        val v = BooleanArray(bins)
        for (s in samples) if (s.id == targetId) { val b = (s.tMs / 1000f / FootageAnalysis.BIN_SEC).toInt(); if (b in 0 until bins) v[b] = true }
        return v
    }

    class Visibility(val visible: BooleanArray, val aliasIds: List<Int>)

    /**
     * [visible] plus re-association: the proximity tracker gives a face that left and came back a NEW id, so the target's later bins would stay muted. A track is
     * accepted as the SAME PERSON as the target only if (1) it never shares a video frame with the target track (two faces in one frame are two people), (2) it has at least
     * [ALIAS_MIN_LIP_BINS] lip-on bins, (3) at least [ALIAS_TARGET_SHARE] of its lip-on bins are labelled TARGET_ONLY by the AUDIO plan (voice evidence, independent of the face)
     * and at most [ALIAS_OTHER_MAX] are labelled OTHER_ONLY. Errors are safe: an alias can only keep bins the audio plan already calls target; it never unmutes OTHER_ONLY bins.
     * PROVISIONAL numbers.
     */
    fun visibleWithAliases(targetId: Int, labels: Array<FootageAnalysis.Seg>): Visibility {
        val base = visible(targetId)
        val frameIdsOfTarget = samples.filter { it.id == targetId }.map { it.tMs }.toHashSet()
        val aliases = ArrayList<Int>()
        val out = base.copyOf()
        for (id in trackIds()) {
            if (id == targetId) continue
            if (samples.any { it.id == id && it.tMs in frameIdsOfTarget }) continue
            val s2 = samples.filter { it.id == id && it.lip.isFinite() }
            val lipOn = FootageWindows.lipOn(FootageWindows.binLip(FloatArray(s2.size) { s2[it].tMs / 1000f }, FloatArray(s2.size) { s2[it].lip }, durationSec), LIP_ON)
            val onBins = lipOn.indices.filter { lipOn[it] && it < labels.size }
            if (onBins.size < ALIAS_MIN_LIP_BINS) continue
            val t = onBins.count { labels[it] == FootageAnalysis.Seg.TARGET_ONLY }.toFloat() / onBins.size
            val o = onBins.count { labels[it] == FootageAnalysis.Seg.OTHER_ONLY }.toFloat() / onBins.size
            if (t < ALIAS_TARGET_SHARE || o > ALIAS_OTHER_MAX) continue
            aliases.add(id)
            val seen = visible(id)
            for (b in out.indices) if (seen[b]) out[b] = true
        }
        return Visibility(out, aliases)
    }

    class Series(val targetLipOn: BooleanArray, val othersOff: BooleanArray?, val otherTrackCount: Int)

    /** Per-bin evidence for TapProposal.propose. [othersOff] is null when no other face was ever tracked (a proposal is then WEAK). */
    fun series(targetId: Int): Series {
        require(samples.any { it.id == targetId }) { "unknown face track" }
        val bins = Math.ceil((durationSec / FootageAnalysis.BIN_SEC).toDouble()).toInt()
        fun binned(id: Int): FloatArray {
            val s = samples.filter { it.id == id && it.lip.isFinite() }
            return FootageWindows.binLip(FloatArray(s.size) { s[it].tMs / 1000f }, FloatArray(s.size) { s[it].lip }, durationSec)
        }
        val target = FootageWindows.lipOn(binned(targetId), LIP_ON)
        val others = trackIds().filter { it != targetId }.map { binned(it) }.filter { b -> b.any { it.isFinite() } }
        require(target.size == bins)
        return Series(target, FootageWindows.othersOff(others, OTHERS_OFF), others.size)
    }
}
