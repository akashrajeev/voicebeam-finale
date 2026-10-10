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
