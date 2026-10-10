package com.akashrajeev.voicebeam

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.vision.FaceProcessor
import com.akashrajeev.voicebeam.vision.FaceSink
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Face detection benchmark on the demo clip's frames. Each frame goes through
 * the app's real FaceProcessor (MediaPipe on a phone, the ML Kit fallback on an
 * x86_64 emulator) one at a time, so the numbers are per-frame latency, not
 * dropped-frame throughput. Logs a VoiceBeamPerf "detect" summary line.
 */
@RunWith(AndroidJUnit4::class)
class DetectionBenchTest {
    @Test fun faceDetectionRateAndSpeed() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val latchRef = AtomicReference(CountDownLatch(1))
        val lastCount = AtomicInteger(0)
        val proc = FaceProcessor(ctx, FaceSink { _, faces, _, _ -> lastCount.set(faces.size); latchRef.get().countDown() })
        assertTrue("no face backend", proc.available)
        val backend = if (proc.usingFallback) "mlkit" else "mediapipe"
        val frames = (1..480 step 8).toList() // 60 frames spread over the whole 60 s clip
        // Warm-up (first call loads models).
        ctx.assets.open("feed/frames/f0001.jpg").use { BitmapFactory.decodeStream(it) }?.let {
            val l = CountDownLatch(1); latchRef.set(l); proc.process(it); l.await(20, TimeUnit.SECONDS)
        }
        val times = ArrayList<Long>()
        var hit2 = 0; var any = 0; var timeouts = 0
        for (i in frames) {
            val bmp = ctx.assets.open("feed/frames/f%04d.jpg".format(i)).use { BitmapFactory.decodeStream(it) } ?: continue
            val latch = CountDownLatch(1)
            latchRef.set(latch)
            val t0 = SystemClock.elapsedRealtime()
            proc.process(bmp)
            if (!latch.await(10, TimeUnit.SECONDS)) { timeouts++; continue }
            times.add(SystemClock.elapsedRealtime() - t0)
            if (lastCount.get() >= 2) hit2++
            if (lastCount.get() >= 1) any++
        }
        proc.close()
        assertTrue("no detections timed", times.isNotEmpty())
        val sorted = times.sorted()
        val avg = times.average()
        val p50 = sorted[sorted.size / 2]
        val p90 = sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)]
        Log.i("VoiceBeamPerf", "detect backend=$backend frames=${times.size} both-faces=$hit2 (" + (hit2 * 100 / times.size) +
            "%) any-face=$any timeouts=$timeouts avg_ms=" + "%.0f".format(avg) + " p50_ms=$p50 p90_ms=$p90 fps=" + "%.1f".format(1000.0 / avg))
        assertTrue("both faces found in under half the frames", hit2 * 2 >= times.size)
    }
}
