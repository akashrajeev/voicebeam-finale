package com.akashrajeev.voicebeam.ml

import ai.onnxruntime.*
import com.akashrajeev.voicebeam.core.AvTseWindowing
import java.io.File
import java.nio.FloatBuffer

/**
 * Runs the 11-graph AV_MossFormer2_TSE_16K chain from a model pack dir (visual, head, mid0..mid7, tail .onnx + .onnx.data).
 * Stage-major loop: each graph is loaded ONCE and run over all windows; intermediates spill to cache files (RAM bounded).
 * Inputs: audio 16k mono peak-normalised FloatArray; video [frames][112*112] normalised (AvTseVisualPrep), 25 fps, >= audio_len*25 frames
 * (pad by repeating last frame like upstream mode='edge').
 */
class AvTseRunner(private val packDir: File, private val cacheDir: File, private val threads: Int = 4) {
    private val env = OrtEnvironment.getEnvironment()
    private fun opts() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }
    private fun open(name: String) = env.createSession(File(packDir, "$name.onnx").absolutePath, opts())

    private fun spill(name: String, t: FloatArray) = File(cacheDir, name).outputStream().channel.use { ch ->
        val bb = java.nio.ByteBuffer.allocateDirect(t.size * 4).order(java.nio.ByteOrder.nativeOrder()); bb.asFloatBuffer().put(t); ch.write(bb) }
    private fun load(name: String, n: Int): FloatArray = File(cacheDir, name).inputStream().channel.use { ch ->
        val bb = java.nio.ByteBuffer.allocateDirect(n * 4).order(java.nio.ByteOrder.nativeOrder()); ch.read(bb); bb.flip()
        FloatArray(n).also { bb.asFloatBuffer().get(it) } }

    fun run(audio: FloatArray, video: Array<FloatArray>, onProgress: (Float) -> Unit = {}): FloatArray {
        val starts = AvTseWindowing.starts(audio.size)
        val nW = starts.size
        val S = 5999; val C = 512
        var step = 0; val totalSteps = 11
        fun tick() { step++; onProgress(step / totalSteps.toFloat()) }
        // visual: per window [1,75,112,112] -> [1,256,75]
        open("visual").use { s ->
            for ((w, st) in starts.withIndex()) {
                val v0 = AvTseWindowing.videoStart(st)
                val buf = FloatBuffer.allocate(75 * 112 * 112)
                for (f in 0 until 75) buf.put(video[minOf(v0 + f, video.size - 1)])
                buf.rewind()
                OnnxTensor.createTensor(env, buf, longArrayOf(1, 75, 112, 112)).use { t ->
                    s.run(mapOf("video" to t)).use { r -> spill("vis_$w", flat(r[0] as OnnxTensor)) } }
            }
        }; tick()
        // head: -> seq [1,5999,512], mixture_w [1,512,5999]
        open("head").use { s ->
            for ((w, st) in starts.withIndex()) {
                val a = FloatBuffer.wrap(audio.copyOfRange(st, minOf(st + AvTseWindowing.WINDOW, audio.size)).copyOf(AvTseWindowing.WINDOW))
                OnnxTensor.createTensor(env, a, longArrayOf(1, 48000)).use { ta ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(load("vis_$w", 256 * 75)), longArrayOf(1, 256, 75)).use { tv ->
                        s.run(mapOf("audio" to ta, "vis_emb" to tv)).use { r ->
                            val seq = flat(r[0] as OnnxTensor); spill("seq_$w", seq); spill("skip_$w", seq); spill("mw_$w", flat(r[1] as OnnxTensor)) } } }
            }
        }; tick()
        for (k in 0 until 8) {
            open("mid$k").use { s ->
                for (w in 0 until nW) OnnxTensor.createTensor(env, FloatBuffer.wrap(load("seq_$w", S * C)), longArrayOf(1, S.toLong(), C.toLong())).use { t ->
                    s.run(mapOf("seq_in" to t)).use { r -> spill("seq_$w", flat(r[0] as OnnxTensor)) } }
            }; tick()
        }
        val out = FloatArray(audio.size)
        open("tail").use { s ->
            for ((w, st) in starts.withIndex()) {
                fun T(n: String, shape: LongArray) = OnnxTensor.createTensor(env, FloatBuffer.wrap(load(n, shape.fold(1L) { a, b -> a * b }.toInt())), shape)
                T("seq_$w", longArrayOf(1, 5999, 512)).use { a -> T("skip_$w", longArrayOf(1, 5999, 512)).use { b -> T("mw_$w", longArrayOf(1, 512, 5999)).use { c ->
                    s.run(mapOf("seq_in" to a, "seq_skip" to b, "mixture_w" to c)).use { r ->
                        AvTseWindowing.place(out, flat(r[0] as OnnxTensor), st, w == 0, w == nW - 1) } } } }
            }
        }; tick()
        cacheDir.listFiles()?.forEach { it.delete() }
        return out
    }
    private fun flat(t: OnnxTensor): FloatArray { val fb = t.floatBuffer; return FloatArray(fb.remaining()).also { fb.get(it) } }
}
