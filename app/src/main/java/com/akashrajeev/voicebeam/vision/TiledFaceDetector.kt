package com.akashrajeev.voicebeam.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.akashrajeev.voicebeam.core.Box
import com.akashrajeev.voicebeam.core.FaceObservation
import com.akashrajeev.voicebeam.core.FaceTiling
import com.akashrajeev.voicebeam.core.FaceTiling.NRect
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.hypot

/**
 * Offline-lane face detector for wide shots. Runs the MediaPipe FaceLandmarker in IMAGE
 * mode (each call independent, synchronous) on the full frame and, when the full frame
 * finds fewer faces than expected, on overlapping square tiles; results are merged by
 * [FaceTiling.merge]. The short-range detector misses small faces at full-frame scale
 * (group interview clips: 1 of 3 people found) - tiling gives each face more of the model input.
 *
 * Cost: up to (1 + tiles) inferences per analysed frame. Tiles run only when the full
 * frame found fewer faces than the most seen so far (or [tileAtLeast]), plus every
 * [forceTileEvery]-th frame so a person who appears later is still picked up.
 * Unmeasured on the phone: time it before relying on it.
 */
class TiledFaceDetector(context: Context, private val tileAtLeast: Int = 2, private val forceTileEvery: Int = 4) {
    private var landmarker: FaceLandmarker? = null
    private var maxSeen = 0
    private var frameNo = 0
    var tiledFrames = 0; private set
    var totalFrames = 0; private set

    val available: Boolean get() = landmarker != null

    init {
        landmarker = create(context, Delegate.GPU) ?: create(context, Delegate.CPU)
    }

    private fun create(context: Context, delegate: Delegate): FaceLandmarker? = try {
        val base = BaseOptions.builder().setModelAssetPath("models/face_landmarker.task").setDelegate(delegate).build()
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(4)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .build()
        FaceLandmarker.createFromOptions(context, opts)
    } catch (t: Throwable) {
        Log.w("VoiceBeamVision", "tiled landmarker init failed on $delegate", t); null
    }

    private class Found(val rect: NRect, val lipRatio: Float)

    private fun detect(lm: FaceLandmarker, bmp: Bitmap): List<Found> {
        val r = lm.detect(BitmapImageBuilder(bmp).build())
        return r.faceLandmarks().map { pts ->
            var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
            for (p in pts) {
                minX = minOf(minX, p.x()); minY = minOf(minY, p.y())
                maxX = maxOf(maxX, p.x()); maxY = maxOf(maxY, p.y())
            }
            val faceH = hypot(pts[10].x() - pts[152].x(), pts[10].y() - pts[152].y()).coerceAtLeast(1e-4f)
            val lipGap = hypot(pts[13].x() - pts[14].x(), pts[13].y() - pts[14].y())
            Found(NRect(minX, minY, maxX, maxY), lipGap / faceH)
        }
    }

    /** Faces in [bmp] (upright ARGB_8888), boxes normalised to the whole frame. Empty list on failure. */
    fun faces(bmp: Bitmap): List<FaceObservation> {
        val lm = landmarker ?: return emptyList()
        totalFrames++
        frameNo++
        try {
            val full = detect(lm, bmp)
            val wide = bmp.width != bmp.height
            val needTiles = wide && (full.size < maxOf(maxSeen, tileAtLeast) || frameNo % forceTileEvery == 0)
            val all = ArrayList<Found>(full)
            if (needTiles) {
                tiledFrames++
                for (tile in FaceTiling.tiles(bmp.width, bmp.height)) {
                    val crop = Bitmap.createBitmap(bmp, tile.x, tile.y, tile.w, tile.h)
                    try {
                        for (f in detect(lm, crop)) all += Found(FaceTiling.toFrame(tile, bmp.width, bmp.height, f.rect), f.lipRatio)
                    } finally { crop.recycle() }
                }
            }
            val keep = if (all.size <= 1) all.indices.toList() else FaceTiling.merge(all.map { it.rect })
            val out = keep.map { i ->
                val f = all[i]
                FaceObservation(Box(f.rect.l, f.rect.t, f.rect.r, f.rect.b), f.lipRatio)
            }
            if (out.size > maxSeen) maxSeen = out.size
            return out
        } catch (t: Throwable) {
            Log.w("VoiceBeamVision", "tiled detect failed", t)
            return emptyList()
        }
    }

    fun close() { landmarker?.close(); landmarker = null }
}
