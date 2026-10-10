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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 *
 * Two timestamp modes. [process] stamps frames with the wall clock (live camera,
 * debug feed). [processAt]/[processAtBlocking] stamp frames with the caller's own
 * source time - decoded-video PTS in ms, monotonically increasing, which
 * RunningMode.LIVE_STREAM requires. In both modes the sink receives the timestamp
 * MediaPipe returns for the frame (the submitted stamp), never a callback-time
 * wall clock, so downstream consumers can run on video time.
 */
class FaceProcessor(context: Context, private val sink: FaceSink) {

    private var landmarker: FaceLandmarker? = null
    private var mlkit: Any? = null
    private var mlkitDetect: java.lang.reflect.Method? = null
    private var mlkitClose: java.lang.reflect.Method? = null
    @Volatile private var lastW = 0
    @Volatile private var lastH = 0
    @Volatile private var busy = false
    @Volatile private var pending: CountDownLatch? = null

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
            .setErrorListener { e -> Log.w("VoiceBeamVision", "landmarker error", e); busy = false; pending?.countDown(); pending = null }
            .build()
        FaceLandmarker.createFromOptions(context, opts)
    } catch (t: Throwable) {
        Log.w("VoiceBeamVision", "landmarker init failed on $delegate", t); null
    }

    /** Process one upright frame at wall-clock time (live camera, debug feed); frames are dropped while a detection is in flight. */
    fun process(bmp: Bitmap) {
        val mk = mlkit
        if (mk != null) {
            try { mlkitDetect?.invoke(mk, bmp) } catch (t: Throwable) { Log.w("VoiceBeamVision", "mlkit detect failed", t) }
            return
        }
        submit(bmp, SystemClock.uptimeMillis(), null)
    }

    /**
     * Process one upright frame stamped with its own source time (decoded-video PTS
     * in ms, monotonically increasing). Async like [process]: returns false when a
     * detection is in flight. MediaPipe only - the debug ML Kit fallback cannot take
     * caller timestamps, so it reports false here.
     */
    fun processAt(bmp: Bitmap, ptsMs: Long): Boolean = submit(bmp, ptsMs, null)

    /**
     * [processAt] that waits for the frame's own result instead of dropping while
     * busy: the offline lane feeds decoded frames one at a time and must not lose
     * any (lost frames read as "mouth still" in the 0.5 s bins). Returns false when
     * the detector is unavailable, the submit failed, or the wait timed out.
     */
    fun processAtBlocking(bmp: Bitmap, ptsMs: Long, timeoutMs: Long = 5000): Boolean {
        val done = CountDownLatch(1)
        if (!submit(bmp, ptsMs, done)) return false
        return try { done.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (t: InterruptedException) { Thread.currentThread().interrupt(); false }
    }

    private fun submit(bmp: Bitmap, tsMs: Long, done: CountDownLatch?): Boolean {
        val lm = landmarker
        if (lm == null || busy) return false
        lastW = bmp.width; lastH = bmp.height
        busy = true
        pending = done
        try {
            lm.detectAsync(BitmapImageBuilder(bmp).build(), tsMs)
            return true
        } catch (t: Throwable) {
            busy = false
            pending = null
            Log.w("VoiceBeamVision", "detect failed", t)
            return false
        }
    }

    private fun handle(r: FaceLandmarkerResult) {
        busy = false
        pending?.countDown()
        pending = null
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
        // Preserve the frame's own timestamp (live: wall clock at submit; offline: video PTS).
        sink.onFaces(r.timestampMs(), faces, lastW, lastH)
    }

    fun close() {
        landmarker?.close(); landmarker = null
        val mk = mlkit; mlkit = null
        if (mk != null) try { mlkitClose?.invoke(mk) } catch (_: Throwable) {}
    }
}
