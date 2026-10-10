package com.akashrajeev.voicebeam.core

/** Normalised (0..1) box in upright camera-image coordinates. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float, pad: Float = 0f): Boolean =
        x >= left - pad && x <= right + pad && y >= top - pad && y <= bottom + pad
}

/** One face seen in one frame. */
data class FaceObservation(val box: Box, val mouthOpenness: Float)

/**
 * Maps between the upright analysis image (normalised 0..1) and a view that shows
 * the same image with "fill center" scaling (the default for CameraX PreviewView).
 */
class FillCenterMapper(
    private val imageW: Float,
    private val imageH: Float,
    private val viewW: Float,
    private val viewH: Float,
    private val mirrored: Boolean,
) {
    private val scale = maxOf(viewW / imageW, viewH / imageH)
    private val offX = (viewW - imageW * scale) / 2f
    private val offY = (viewH - imageH * scale) / 2f

    fun toView(nx: Float, ny: Float): Pair<Float, Float> {
        val x = if (mirrored) 1f - nx else nx
        return Pair(offX + x * imageW * scale, offY + ny * imageH * scale)
    }

    fun toImage(vx: Float, vy: Float): Pair<Float, Float> {
        val nx = ((vx - offX) / scale) / imageW
        val ny = ((vy - offY) / scale) / imageH
        return Pair(if (mirrored) 1f - nx else nx, ny)
    }
}

/**
 * Same as [FillCenterMapper] but for "fit center" scaling (letterboxed), used by
 * the debug demo-feed player so taps and face rings line up with the video.
 */
class FitCenterMapper(
    private val imageW: Float,
    private val imageH: Float,
    private val viewW: Float,
    private val viewH: Float,
    private val mirrored: Boolean,
) {
    private val scale = minOf(viewW / imageW, viewH / imageH)
    private val offX = (viewW - imageW * scale) / 2f
    private val offY = (viewH - imageH * scale) / 2f

    fun toView(nx: Float, ny: Float): Pair<Float, Float> {
        val x = if (mirrored) 1f - nx else nx
        return Pair(offX + x * imageW * scale, offY + ny * imageH * scale)
    }

    fun toImage(vx: Float, vy: Float): Pair<Float, Float> {
        val nx = ((vx - offX) / scale) / imageW
        val ny = ((vy - offY) / scale) / imageH
        return Pair(if (mirrored) 1f - nx else nx, ny)
    }
}
