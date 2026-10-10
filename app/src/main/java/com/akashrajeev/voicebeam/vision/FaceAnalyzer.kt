package com.akashrajeev.voicebeam.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.akashrajeev.voicebeam.core.Box
import com.akashrajeev.voicebeam.core.FaceObservation
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.hypot

/**
 * CameraX analyzer that runs MediaPipe Face Landmarker on every frame and
 * reports faces as boxes plus a mouth-openness value (inner-lip gap divided
 * by face height), all in upright, normalised image coordinates.
 */
class FaceAnalyzer(
    context: Context,
    private val onFaces: (timeMs: Long, faces: List<FaceObservation>, width: Int, height: Int) -> Unit,
    /** Gesture sampling interval in ms while consent is asked or a lock is active, or 0 when gestures are not needed. */
    private val gestureIntervalMs: () -> Long = { 0L },
    private val onHands: (timeMs: Long, hands: List<com.akashrajeev.voicebeam.core.HandObservation>) -> Unit = { _, _ -> },
) : ImageAnalysis.Analyzer {

    private var landmarker: FaceLandmarker? = null
    @Volatile private var lastW = 0
    @Volatile private var lastH = 0
    @Volatile private var busy = false

    private val hands = HandGesture(context)
    private var lastGestureMs = 0L
    private var submittedMs = 0L
    private var sensorNs = 0L
    private var diagnosticAtMs = SystemClock.uptimeMillis()
    private var processedFrames = 0
    private var droppedFrames = 0

    init {
        landmarker = create(context, Delegate.GPU) ?: create(context, Delegate.CPU)
    }

    private fun create(context: Context, delegate: Delegate): FaceLandmarker? = try {
        val base = BaseOptions.builder().setModelAssetPath("models/face_landmarker.task").setDelegate(delegate).build()
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(4)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { r: FaceLandmarkerResult, _ -> handle(r) }
            .setErrorListener { e -> Log.w("VoiceBeamVision", "landmarker error", e); busy = false }
            .build()
        FaceLandmarker.createFromOptions(context, opts)
    } catch (t: Throwable) {
        Log.w("VoiceBeamVision", "landmarker init failed on $delegate", t); null
    }

    // Reused every frame: the RGBA copy of the camera frame and, when the sensor is rotated, the upright copy.
    // MediaPipe is done with the bitmap once the result listener runs (busy == false), so reuse is safe.
    private var frameBitmap: Bitmap? = null
    private var uprightBitmap: Bitmap? = null
    private val rotation = Matrix()

    override fun analyze(image: ImageProxy) {
        val lm = landmarker
        if (lm == null || busy) { droppedFrames++; image.close(); return }
        try {
            val bmp = frameBitmapFor(image)
            val rot = image.imageInfo.rotationDegrees
            val upright = if (rot != 0) rotateInto(bmp, rot) else bmp
            lastW = upright.width; lastH = upright.height
            val gi = gestureIntervalMs()
            val nowG = SystemClock.uptimeMillis()
            if (gi > 0 && nowG - lastGestureMs >= gi) {
                lastGestureMs = nowG
                onHands(nowG, hands.recognize(upright))
            }
            busy = true
            submittedMs = SystemClock.uptimeMillis()
            sensorNs = image.imageInfo.timestamp
            lm.detectAsync(BitmapImageBuilder(upright).build(), submittedMs)
        } catch (t: Throwable) {
            busy = false
            Log.w("VoiceBeamVision", "analyze failed", t)
        } finally {
            image.close()
        }
    }

    /** Copies the frame into a reusable bitmap when it is tightly packed RGBA; otherwise falls back to toBitmap(). */
    private fun frameBitmapFor(image: ImageProxy): Bitmap {
        val plane = image.planes.firstOrNull()
        if (image.format == PixelFormat.RGBA_8888 && plane != null && plane.pixelStride == 4 && plane.rowStride == image.width * 4) {
            var b = frameBitmap
            if (b == null || b.width != image.width || b.height != image.height) {
                b = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                frameBitmap = b
            }
            plane.buffer.rewind()
            b!!.copyPixelsFromBuffer(plane.buffer)
            return b
        }
        return image.toBitmap()
    }

    private fun rotateInto(src: Bitmap, degrees: Int): Bitmap {
        val swap = degrees == 90 || degrees == 270
        val w = if (swap) src.height else src.width
        val h = if (swap) src.width else src.height
        var dst = uprightBitmap
        if (dst == null || dst.width != w || dst.height != h) {
            dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            uprightBitmap = dst
        }
        // Same transform Bitmap.createBitmap(src, 0, 0, w, h, postRotate(deg), true) applies:
        // rotate about the origin, then shift so the result starts at (0, 0).
        rotation.reset()
        rotation.postRotate(degrees.toFloat())
        when (degrees) {
            90 -> rotation.postTranslate(src.height.toFloat(), 0f)
            180 -> rotation.postTranslate(src.width.toFloat(), src.height.toFloat())
            270 -> rotation.postTranslate(0f, src.width.toFloat())
        }
        Canvas(dst!!).drawBitmap(src, rotation, null)
        return dst
    }

    private fun handle(r: FaceLandmarkerResult) {
        try {
            com.akashrajeev.voicebeam.core.withCallbackCleanup(cleanup = { busy = false }) {
                val receivedMs = SystemClock.uptimeMillis()
                val sourceMs = r.timestampMs()
                processedFrames++
                val faces = r.faceLandmarks().map { pts ->
                    var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
                    for (p in pts) {
                        minX = minOf(minX, p.x()); minY = minOf(minY, p.y())
                        maxX = maxOf(maxX, p.x()); maxY = maxOf(maxY, p.y())
                    }
                    val faceH = hypot(pts[10].x() - pts[152].x(), pts[10].y() - pts[152].y()).coerceAtLeast(1e-4f)
                    val lipGap = hypot(pts[13].x() - pts[14].x(), pts[13].y() - pts[14].y())
                    FaceObservation(Box(minX, minY, maxX, maxY), lipGap / faceH)
                }
                // Preserve the MediaPipe input timestamp; old frames must not look fresh.
                onFaces(sourceMs, faces, lastW, lastH)
                if (receivedMs - diagnosticAtMs >= 1000L) {
                    val elapsed = (receivedMs - diagnosticAtMs).coerceAtLeast(1)
                    com.akashrajeev.voicebeam.engine.Diagnostics.event("vision sourceMs=" + sourceMs +
                        " sensorNs=" + sensorNs + " receivedMs=" + receivedMs +
                        " inferenceMs=" + (receivedMs - submittedMs) +
                        " processedFps=" + (processedFrames * 1000f / elapsed) +
                        " droppedFrames=" + droppedFrames + " faces=" + faces.size +
                        " mouthOpenness=" + faces.joinToString(",") { it.mouthOpenness.toString() })
                    diagnosticAtMs = receivedMs; processedFrames = 0; droppedFrames = 0
                }
            }
        } catch (t: Throwable) {
            Log.w("VoiceBeamVision", "callback failed: " + t.javaClass.simpleName)
        }
    }

    val available: Boolean get() = landmarker != null

    fun close() { landmarker?.close(); landmarker = null; hands.close(); frameBitmap = null; uprightBitmap = null }
}
