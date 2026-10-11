package com.akashrajeev.voicebeam.ml

import ai.onnxruntime.*
import java.io.File
import java.nio.FloatBuffer

/** Times mid0.onnx (one of 8 identical-cost stages; ~98% of total runtime is 8 x mid) at several thread counts. Returns a report string. */
object AvTseBenchmark {
    fun run(packDir: File, runs: Int = 5, threadCounts: List<Int> = listOf(2, 4, 6)): String {
        val env = OrtEnvironment.getEnvironment()
        val sb = StringBuilder("AV-TSE mid0 benchmark, input [1,5999,512] fp32\n")
        val x = FloatArray(5999 * 512) { ((it * 2654435761L % 1000) / 1000f - 0.5f) }
        for (th in threadCounts) {
            val o = OrtSession.SessionOptions().apply { setIntraOpNumThreads(th); setCPUArenaAllocator(false); setMemoryPatternOptimization(false) }
            val t0 = System.nanoTime()
            env.createSession(File(packDir, "mid0.onnx").absolutePath, o).use { s ->
                val load = (System.nanoTime() - t0) / 1e9
                val times = ArrayList<Double>()
                repeat(runs + 1) { i ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 5999, 512)).use { t ->
                        val a = System.nanoTime(); s.run(mapOf("seq_in" to t)).close(); val d = (System.nanoTime() - a) / 1e9
                        if (i > 0) times.add(d) } }   // first run = warmup, excluded
                val med = times.sorted()[times.size / 2]
                // projection: 8 mid stages x windows (30s clip = 16 windows at stride 1.8s) + ~0.8s/window visual/head/tail on x86 (assume same ratio)
                sb.append("threads=$th load=%.1fs runs=%s median=%.2fs -> est 30s clip (16 win x 8 mid) = %.1f min (mid only)\n"
                    .format(load, times.joinToString { "%.2f".format(it) }, med, med * 8 * 16 / 60))
            }
        }
        return sb.toString()
    }
}
