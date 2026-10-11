package com.akashrajeev.voicebeam.core

import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometry for tiled face detection. The MediaPipe face detector is a short-range
 * model: in a wide group shot each face is only a few percent of the frame and the
 * full-frame pass misses it. Cropping square tiles (side = min(W,H), 50% step) gives
 * each face a bigger share of the model input. Tile results are mapped back to
 * frame-normalised coordinates and merged by IoU so one face found twice counts once.
 */
object FaceTiling {
    /** Pixel rect: x, y, side lengths w and h. */
    data class Tile(val x: Int, val y: Int, val w: Int, val h: Int)

    /** Frame-normalised rectangle (0..1). */
    data class NRect(val l: Float, val t: Float, val r: Float, val b: Float) {
        val area: Float get() = max(0f, r - l) * max(0f, b - t)
    }

    /** Square tiles of side min(W,H) stepping by half a side along the long axis. One tile when the frame is square. */
    fun tiles(width: Int, height: Int, stepFrac: Float = 0.5f): List<Tile> {
        require(width > 0 && height > 0)
        val s = min(width, height)
        val long = max(width, height)
        if (long <= s) return listOf(Tile(0, 0, s, s))
        val n = kotlin.math.ceil((long - s) / (stepFrac * s)).toInt() + 1
        val out = ArrayList<Tile>(n)
        for (i in 0 until n) {
            val off = if (n == 1) 0 else Math.round(i.toFloat() * (long - s) / (n - 1))
            out += if (width >= height) Tile(off, 0, s, s) else Tile(0, off, s, s)
        }
        return out
    }

    /** Map a tile-normalised rect back to frame-normalised coordinates. */
    fun toFrame(tile: Tile, frameW: Int, frameH: Int, r: NRect): NRect = NRect(
        (tile.x + r.l * tile.w) / frameW,
        (tile.y + r.t * tile.h) / frameH,
        (tile.x + r.r * tile.w) / frameW,
        (tile.y + r.b * tile.h) / frameH,
    )

    fun iou(a: NRect, b: NRect): Float {
        val iw = min(a.r, b.r) - max(a.l, b.l)
        val ih = min(a.b, b.b) - max(a.t, b.t)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    /**
     * Greedy merge, larger boxes first (a face seen whole in a tile beats one cut by a tile edge).
     * A box is a duplicate when IoU with a kept box >= [iouMin] or when it lies mostly inside a kept box.
     * Returns the indices of kept inputs, in input order.
     */
    fun merge(boxes: List<NRect>, iouMin: Float = 0.35f, containedMin: Float = 0.8f): List<Int> {
        val order = boxes.indices.sortedByDescending { boxes[it].area }
        val kept = ArrayList<Int>()
        for (i in order) {
            val b = boxes[i]
            val dup = kept.any { k ->
                val o = boxes[k]
                val iw = min(b.r, o.r) - max(b.l, o.l)
                val ih = min(b.b, o.b) - max(b.t, o.t)
                val inter = if (iw > 0f && ih > 0f) iw * ih else 0f
                iou(b, o) >= iouMin || (b.area > 0f && inter / b.area >= containedMin)
            }
            if (!dup) kept += i
        }
        return kept.sorted()
    }
}
