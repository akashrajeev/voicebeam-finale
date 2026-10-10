package com.akashrajeev.voicebeam.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.akashrajeev.voicebeam.core.FaceTrackBinner

/**
 * Decoded-video face feed for the offline lane: pulls frames with MediaMetadataRetriever
 * at a fixed rate (never below 8 fps - LipActivity needs about 4 samples inside its
 * 700 ms window or a talking mouth reads as still), rotates them upright, and runs
 * them through the real FaceProcessor in video-PTS time via processAtBlocking
 * (one frame at a time, none dropped). Tracks land in a FaceTrackBinner, the input
 * of TapProposal. MediaPipe only: where the debug ML Kit fallback would run
 * (x86_64 emulators) analyze() returns null and the typed reference stays the path.
 */
object OfflineFaceFeed {

    class Result(val durationSec: Float, val framesAnalysed: Int, val binner: FaceTrackBinner)

    /** Analyse [uri]; null when the video cannot be read, no MediaPipe backend is available, nothing was analysed, or cancelled. */
    fun analyze(context: Context, uri: Uri, fps: Int = 8, isCancelled: () -> Boolean = { false }): Result? {
        require(fps >= 8) { "LipActivity needs at least 8 fps" }
        val retriever = MediaMetadataRetriever()
        var proc: FaceProcessor? = null
        try {
            retriever.setDataSource(context, uri)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val binner = FaceTrackBinner(durMs / 1000f)
            val processor = FaceProcessor(context, FaceSink { t, faces, _, _ -> binner.add(t, faces) })
            proc = processor
            if (!processor.available || processor.usingFallback) return null
            val stepMs = 1000L / fps
            var analysed = 0
            var t = 0L
            while (t < durMs) {
                if (isCancelled()) return null
                val frame = try {
                    retriever.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (e: Throwable) {
                    Log.w("VoiceBeamVision", "frame at $t ms failed: ${e.message}")
                    null
                }
                if (frame != null) {
                    val upright = rotateUpright(frame, rotation)
                    processor.processAtBlocking(upright, t)
                    analysed++
                    if (upright !== frame) frame.recycle()
                    upright.recycle()
                }
                t += stepMs
            }
            if (analysed == 0) return null
            return Result(durMs / 1000f, analysed, binner)
        } catch (e: Throwable) {
            Log.w("VoiceBeamVision", "offline face feed failed", e)
            return null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
            proc?.close()
        }
    }

    /** Rotation metadata is not baked into retriever frames: rotate upright, and force ARGB_8888 for the landmarker. */
    private fun rotateUpright(src: Bitmap, degrees: Int): Bitmap {
        val argb = if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
        if (degrees == 0) return argb
        val m = Matrix()
        m.postRotate(degrees.toFloat())
        val out = Bitmap.createBitmap(argb, 0, 0, argb.width, argb.height, m, true)
        if (argb !== src) argb.recycle()
        return out
    }
}
