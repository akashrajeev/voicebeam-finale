package com.akashrajeev.voicebeam.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.akashrajeev.voicebeam.core.Box
import com.akashrajeev.voicebeam.core.FaceObservation
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Debug builds only: face backend for machines without the MediaPipe native
 * library (x86_64 emulators). Uses the bundled ML Kit model, so no Play
 * services are needed. Mouth openness is the vertical gap between the inner
 * lip contours, normalised by the face box height (same contract as MediaPipe).
 */
class MlKitBackend(context: Context, private val sink: FaceSink) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.1f)
            .build()
    )

    @Volatile private var busy = false
    private var calls = 0

    fun detect(bmp: Bitmap) {
        if (busy) return
        busy = true
        calls++
        val n = calls
        detector.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { faces ->
                busy = false
                if (n % 20 == 1) {
                    val lips = faces.joinToString { f ->
                        val up = f.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points
                        val lo = f.getContour(FaceContour.LOWER_LIP_TOP)?.points
                        if (!up.isNullOrEmpty() && !lo.isNullOrEmpty())
                            "%.3f".format((lo.map { it.y }.average() - up.map { it.y }.average()) / f.boundingBox.height().coerceAtLeast(1))
                        else "?"
                    }
                    android.util.Log.i("VoiceBeamVision", "mlkit faces=" + faces.size + " call=" + n + " lips=" + lips)
                }
                val obs = faces.map { f ->
                    val b = f.boundingBox
                    val box = Box(
                        (b.left / bmp.width.toFloat()).coerceIn(0f, 1f),
                        (b.top / bmp.height.toFloat()).coerceIn(0f, 1f),
                        (b.right / bmp.width.toFloat()).coerceIn(0f, 1f),
                        (b.bottom / bmp.height.toFloat()).coerceIn(0f, 1f),
                    )
                    val up = f.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points
                    val lo = f.getContour(FaceContour.LOWER_LIP_TOP)?.points
                    var open = 0f
                    if (!up.isNullOrEmpty() && !lo.isNullOrEmpty()) {
                        val uy = up.map { it.y }.average().toFloat()
                        val ly = lo.map { it.y }.average().toFloat()
                        open = (ly - uy) / b.height().coerceAtLeast(1).toFloat()
                    }
                    FaceObservation(box, open)
                }
                sink.onFaces(SystemClock.uptimeMillis(), obs, bmp.width, bmp.height)
            }
            .addOnFailureListener { busy = false }
    }

    fun close() { detector.close() }
}
