package com.akashrajeev.voicebeam.core

import kotlin.math.hypot

/** A face we have followed across frames. */
data class TrackedFace(
    val id: Int,
    val box: Box,
    val speaking: Float,
    val lastSeenMs: Long,
)

/**
 * Keeps stable IDs for faces across frames (nearest-centre matching) and
 * remembers which face the user tapped. The lock survives short dropouts
 * (a turned head, a blink of occlusion) and follows the person as they move.
 */
class FaceTracker(
    private val maxJump: Float = 0.22f,
    private val forgetAfterMs: Long = 1500,
) {
    private class Track(var id: Int, var box: Box, var lastSeen: Long, val lips: LipActivity = LipActivity())

    private val tracks = mutableListOf<Track>()
    private var nextId = 1
    var lockedId: Int? = null
        private set

    @Synchronized
    fun update(timeMs: Long, faces: List<FaceObservation>): List<TrackedFace> {
        val unmatched = tracks.toMutableList()
        for (f in faces.sortedByDescending { it.box.width * it.box.height }) {
            val best = unmatched.minByOrNull { hypot(it.box.cx - f.box.cx, it.box.cy - f.box.cy) }
            val dist = best?.let { hypot(it.box.cx - f.box.cx, it.box.cy - f.box.cy) }
            val limit = maxJump + (f.box.width * 0.5f)
            val t = if (best != null && dist != null && dist < limit) {
                unmatched.remove(best); best
            } else {
                Track(nextId++, f.box, timeMs).also { tracks.add(it) }
            }
            t.box = f.box
            t.lastSeen = timeMs
            t.lips.add(timeMs, f.mouthOpenness)
        }
        tracks.removeAll { timeMs - it.lastSeen > forgetAfterMs && it.id != lockedId }
        val lockedTrack = tracks.firstOrNull { it.id == lockedId }
        if (lockedTrack != null && timeMs - lockedTrack.lastSeen > forgetAfterMs * 4) {
            tracks.remove(lockedTrack)
            lockedId = null
        }
        return snapshot(timeMs)
    }

    @Synchronized
    fun snapshot(timeMs: Long): List<TrackedFace> = tracks.map {
        val visible = timeMs - it.lastSeen < 400
        TrackedFace(it.id, it.box, if (visible) it.lips.score() else 0f, it.lastSeen)
    }

    /** Lock onto the face under (or nearest to) a tap. Returns the locked id or null. */
    @Synchronized
    fun lockAt(nx: Float, ny: Float, nowMs: Long): Int? {
        val visible = tracks.filter { nowMs - it.lastSeen < 800 }
        val hit = visible.firstOrNull { it.box.contains(nx, ny, pad = 0.04f) }
            ?: visible.minByOrNull { hypot(it.box.cx - nx, it.box.cy - ny) }
                ?.takeIf { hypot(it.box.cx - nx, it.box.cy - ny) < 0.25f }
        lockedId = hit?.id
        return lockedId
    }

    /** Which visible face a tap hits, without locking it. */
    @Synchronized
    fun faceAt(nx: Float, ny: Float, nowMs: Long): Int? {
        val visible = tracks.filter { nowMs - it.lastSeen < 800 }
        val hit = visible.firstOrNull { it.box.contains(nx, ny, pad = 0.04f) }
            ?: visible.minByOrNull { hypot(it.box.cx - nx, it.box.cy - ny) }
                ?.takeIf { hypot(it.box.cx - nx, it.box.cy - ny) < 0.25f }
        return hit?.id
    }

    @Synchronized
    fun lockFace(id: Int): Boolean {
        if (tracks.none { it.id == id }) return false
        lockedId = id
        return true
    }

    @Synchronized
    fun unlock() { lockedId = null }

    @Synchronized
    fun reset() { tracks.clear(); lockedId = null }
}
