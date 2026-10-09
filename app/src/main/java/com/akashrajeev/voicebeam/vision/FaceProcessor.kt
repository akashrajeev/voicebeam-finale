package com.akashrajeev.voicebeam.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.akashrajeev.voicebeam.core.Box
import com.akashrajeev.voicebeam.core.FaceObservation
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.hypot

/** Receives faces from any frame source (camera analyzer or debug feed). */
fun interface FaceSink {
    fun onFaces(timeMs: Long, faces: List<FaceObservation>, width: Int, height: Int)
}

/**
 * Runs face + lip landmark detection on upright bitmap frames. Prefers MediaPipe
 * (real phones). When the MediaPipe native library is missing (x86_64 emulators)
 * it falls back to the ML Kit backend that ships only in debug builds; in release
 * builds there is no fallback and face tracking reports itself unavailable.
 */
class FaceProcessor(context: Context, private val sink: FaceSink) {

    private var landmarker: FaceLandmarker? = null
    private var mlkit: Any? = null
    private var mlkitDetect: java.lang.reflect.Method? = null
    private var mlkitClose: java.lang.reflect.Method? = null
    @Volatile private var lastW = 0
    @Volatile private var lastH = 0
    @Volatile private var busy = false

    val available: Boolean get() = landmarker != null || mlkit != null

    /** True when running on the debug-only ML Kit fallback (x86_64 emulators). */
    val usingFallback: Boolean get() = landmarker == null && mlkit != null

    init {
        landmarker = create(context, Delegate.GPU) ?: create(context, Delegate.CPU)
        if (landmarker == null) {
            mlkit = try {
                val cls = Class.forName("com.akashrajeev.voicebeam.vision.MlKitBackend")
                val inst = cls.getConstructor(Context::class.java, FaceSink::class.java).newInstance(context, sink)
                mlkitDetect = cls.getMethod("detect", Bitmap::class.java)
                mlkitClose = cls.getMethod("close")
                Log.i("VoiceBeamVision", "MediaPipe unavailable, using ML Kit face backend")
                inst
            } catch (t: Throwable) {
                Log.w("VoiceBeamVision", "no ML Kit fallback available", t)
                null
            }
        }
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

    /** Process one upright frame; frames are dropped while a detection is in flight. */
    fun process(bmp: Bitmap) {
        val mk = mlkit
        if (mk != null) {
            try { mlkitDetect?.invoke(mk, bmp) } catch (t: Throwable) { Log.w("VoiceBeamVision", "mlkit detect failed", t) }
            return
        }
        val lm = landmarker
        if (lm == null || busy) return
        lastW = bmp.width; lastH = bmp.height
        busy = true
        try {
            lm.detectAsync(BitmapImageBuilder(bmp).build(), SystemClock.uptimeMillis())
        } catch (t: Throwable) {
            busy = false
            Log.w("VoiceBeamVision", "detect failed", t)
        }
    }

    private fun handle(r: FaceLandmarkerResult) {
        busy = false
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
        sink.onFaces(SystemClock.uptimeMillis(), faces, lastW, lastH)
    }

    fun close() {
        landmarker?.close(); landmarker = null
        val mk = mlkit; mlkit = null
        if (mk != null) try { mlkitClose?.invoke(mk) } catch (_: Throwable) {}
    }
}
