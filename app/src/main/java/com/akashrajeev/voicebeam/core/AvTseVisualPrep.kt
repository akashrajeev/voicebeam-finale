package com.akashrajeev.voicebeam.core

import kotlin.math.max
import kotlin.math.min

/**
 * Upstream-exact visual preprocessing (ClearerVoice video_process.py crop_video + evaluate_network):
 *  face bbox -> (centre, half-size bs) median-filtered over time (kernel 13) -> crop
 *  rows [cy-bs, cy+bs*(1+2cs)), cols [cx-bs*(1+cs), cx+bs*(1+cs)), cs=0.40, pad value 110 (all channels)
 *  -> resize 224x224 (bilinear, cv2 half-pixel) -> BGR2GRAY -> centre 112x112 -> /255 -> (x-0.4161)/0.1688.
 * NOTE the box is NOT a mouth ROI: it is a face crop biased downward; the 112 centre crop lands on mouth/lower face.
 * Frames must be 25 fps. Upstream also quantises gray to uint8 and went through XVID compression; we skip the codec.
 */
object AvTseVisualPrep {
    const val CS = 0.40
    const val MEAN = 0.4161f
    const val STD = 0.1688f

    fun medfilt13(a: DoubleArray): DoubleArray {
        // scipy.signal.medfilt kernel 13: zero padded at the edges
        val n = a.size; val out = DoubleArray(n); val buf = DoubleArray(13)
        for (i in 0 until n) {
            for (k in -6..6) { val j = i + k; buf[k + 6] = if (j in 0 until n) a[j] else 0.0 }
            buf.sort(); out[i] = buf[6]
        }
        return out
    }

    /**
     * @param rgba  source frame ARGB ints (Android Bitmap.getPixels), width w, height h
     * @param cx,cy,bs smoothed centre (x,y) and half size in source pixels
     * @return 112*112 floats, row-major, normalised
     */
    fun frameToModel(argb: IntArray, w: Int, h: Int, cx: Double, cy: Double, bs: Double): FloatArray {
        val bsi = (bs * (1 + 2 * CS)).toInt()
        val my = cy + bsi; val mx = cx + bsi
        val top = (my - bs).toInt(); val bottom = (my + bs * (1 + 2 * CS)).toInt()
        val left = (mx - bs * (1 + CS)).toInt(); val right = (mx + bs * (1 + CS)).toInt()
        val ch = bottom - top; val cw = right - left          // crop size in padded coords
        val out = FloatArray(112 * 112)
        // gray of source (with 110 padding), padded coords -> source coords by subtracting bsi
        fun gray(px: Int, py: Int): Float { // padded coords
            val x = px - bsi; val y = py - bsi
            if (x < 0 || y < 0 || x >= w || y >= h) return 110f
            val p = argb[y * w + x]
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            return r * 0.299f + g * 0.587f + b * 0.114f
        }
        val sx = cw / 224.0; val sy = ch / 224.0
        for (oy in 0 until 112) for (ox in 0 until 112) {
            // output pixel (ox,oy) of the 224 grid is at (ox+56, oy+56); cv2 INTER_LINEAR half-pixel mapping
            val fx = (ox + 56 + 0.5) * sx - 0.5; val fy = (oy + 56 + 0.5) * sy - 0.5
            val x0 = Math.floor(fx).toInt(); val y0 = Math.floor(fy).toInt()
            val ax = (fx - x0).toFloat(); val ay = (fy - y0).toFloat()
            fun g(xx: Int, yy: Int) = gray(left + min(max(xx, 0), cw - 1), top + min(max(yy, 0), ch - 1))
            val v = (g(x0, y0) * (1 - ax) + g(x0 + 1, y0) * ax) * (1 - ay) + (g(x0, y0 + 1) * (1 - ax) + g(x0 + 1, y0 + 1) * ax) * ay
            out[oy * 112 + ox] = (Math.round(v) / 255f - MEAN) / STD   // uint8 quantisation like upstream
        }
        return out
    }

    /** Per-frame track (cx, cy, bs) in source pixels. */
    class Track(val cx: DoubleArray, val cy: DoubleArray, val bs: DoubleArray)

    /**
     * Calibrated landmark -> upstream-S3FD-box mapping (fitted on the demo clip: 2 faces x 4 frames; A/B in the real model, see notes).
     * lm* = min/max over ALL 478 FaceLandmarker landmarks, in SOURCE pixels (x*width, y*height) for ONE frame.
     *   bs = 1.04 * max(w,h)/2 ; cx = centre x ; cy = centre y - 0.05*h
     */
    fun landmarkBoxToCrop(minX: Double, minY: Double, maxX: Double, maxY: Double): DoubleArray {
        val w = maxX - minX; val h = maxY - minY
        return doubleArrayOf((minX + maxX) / 2, (minY + maxY) / 2 - 0.05 * h, 1.04 * max(w, h) / 2)  // [cx, cy, bs]
    }

    /** Build a smoothed track (median filter 13 over the 25fps frame sequence) from per-frame landmark crops [cx,cy,bs]. */
    fun smoothTrack(perFrame: List<DoubleArray>): Track {
        val cx = medfilt13(DoubleArray(perFrame.size) { perFrame[it][0] })
        val cy = medfilt13(DoubleArray(perFrame.size) { perFrame[it][1] })
        val bs = medfilt13(DoubleArray(perFrame.size) { perFrame[it][2] })
        return Track(cx, cy, bs)
    }

    /** Source-frame index for 25fps output frame i (nearest to i*40ms), like ffmpeg -r 25. */
    fun sourceFrameFor25fps(i: Int, srcFps: Double, srcFrames: Int): Int = min(srcFrames - 1, Math.round(i * 40.0 / 1000.0 * srcFps).toInt())

    /** Audio normalisation upstream applies before windowing: audio /= max(abs(audio)) (peak normalise). */
    fun peakNormalize(a: FloatArray) {
        var m = 0f; for (v in a) m = max(m, kotlin.math.abs(v))
        if (m > 0f) for (i in a.indices) a[i] /= m
    }
}
