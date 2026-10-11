package com.akashrajeev.voicebeam.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.akashrajeev.voicebeam.core.HandObservation
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer

/**
 * On-device hand gesture check (MediaPipe Gesture Recognizer, model bundled in assets).
 * Synchronous IMAGE mode, called from the camera analyzer thread only while consent is
 * being asked or while a lock is active. Nothing is stored or sent anywhere.
 */
class HandGesture(private val context: Context) {
    private var rec: GestureRecognizer? = null
    private var failed = false

    private fun ensure(): GestureRecognizer? {
        if (rec != null || failed) return rec
        rec = try {
            val base = BaseOptions.builder().setModelAssetPath("models/gesture_recognizer.task").setDelegate(Delegate.CPU).build()
            val opts = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            GestureRecognizer.createFromOptions(context, opts)
        } catch (t: Throwable) {
            Log.w("VoiceBeamVision", "gesture recognizer init failed", t); failed = true; null
        }
        return rec
    }

    /** Hands in the upright frame; empty when none or when the model is unavailable. */
    fun recognize(bmp: Bitmap): List<HandObservation> {
        val r = ensure() ?: return emptyList()
        return try {
            val res = r.recognize(BitmapImageBuilder(bmp).build())
            val out = ArrayList<HandObservation>()
            for (i in res.landmarks().indices) {
                val pts = res.landmarks()[i]
                if (pts.isEmpty()) continue
                val cx = pts.sumOf { it.x().toDouble() }.toFloat() / pts.size
                val cy = pts.sumOf { it.y().toDouble() }.toFloat() / pts.size
                val top = res.gestures().getOrNull(i)?.firstOrNull()
                out.add(HandObservation(top?.categoryName() ?: "None", top?.score() ?: 0f, cx, cy))
            }
            out
        } catch (t: Throwable) {
            Log.w("VoiceBeamVision", "gesture recognize failed", t); emptyList()
        }
    }

    fun close() { try { rec?.close() } catch (_: Throwable) {}; rec = null }
}
