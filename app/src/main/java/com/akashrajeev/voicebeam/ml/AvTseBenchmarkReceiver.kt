package com.akashrajeev.voicebeam.ml

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * adb-triggered AV-TSE benchmark (no UI). Model pack: <externalFilesDir>/avtse/{mid0.onnx,mid0.onnx.data,...}
 *   adb push outd/. /sdcard/Android/data/com.akashrajeev.voicebeam.finale/files/avtse/
 *   adb shell am broadcast -n com.akashrajeev.voicebeam.finale/com.akashrajeev.voicebeam.ml.AvTseBenchmarkReceiver
 * Report goes to logcat (tag AvTseBench) and <externalFilesDir>/avtse-benchmark.txt.
 */
class AvTseBenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val dir = File(context.getExternalFilesDir(null), "avtse")
                val report = if (!File(dir, "mid0.onnx").exists()) "AV-TSE pack missing: ${dir.absolutePath}/mid0.onnx"
                             else AvTseBenchmark.run(dir)
                Log.i("AvTseBench", report)
                File(context.getExternalFilesDir(null), "avtse-benchmark.txt").writeText(report)
            } catch (t: Throwable) {
                Log.e("AvTseBench", "benchmark failed", t)
                try { File(context.getExternalFilesDir(null), "avtse-benchmark.txt").writeText("FAILED: $t") } catch (_: Throwable) {}
            } finally { pending.finish() }
        }.start()
    }
}
