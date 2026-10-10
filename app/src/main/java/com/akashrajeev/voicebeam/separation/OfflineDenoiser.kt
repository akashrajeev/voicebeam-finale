package com.akashrajeev.voicebeam.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.akashrajeev.voicebeam.core.DpdfnetDsp
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * Offline speech denoiser front-end (DPDFNet-2, 16 kHz, stateful streaming ONNX run frame by frame on the whole decoded file).
 * Used ONLY to produce embeddings for clustering. Render and FinalAudioGuard stay on the ORIGINAL audio.
 * Model: assets/models/dpdfnet2.onnx, sha256 below (official sherpa-onnx release asset). Single-threaded session; one instance per call.
 * Cancel is polled every STFT frame (10 ms). Returns a buffer of the same length as the input.
 */
object OfflineDenoiser {
    const val ASSET = "models/dpdfnet2.onnx"
    const val SHA256 = "ce35d6025fc71df0ef10d1540e1b7916837bbfe5f6896deb744508d2cad487a9"

    fun denoise(context: Context, samples: FloatArray, isCancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit = {}): FloatArray {
        val model = context.assets.open(ASSET).use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) }
        require(digest == SHA256) { "Denoiser model hash mismatch" }
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1); setInterOpNumThreads(1) }
        return options.use { opt -> env.createSession(model, opt).use { session ->
            val meta = session.metadata.customMetadata
            require(meta["n_fft"] == "320" && meta["hop_length"] == "160" && meta["window_length"] == "320" && meta["sample_rate"] == "16000" && meta["freq_bins"] == "161") { "Unexpected denoiser profile" }
            val stateSize = meta["state_size"]!!.toInt()
            fun floats(key: String) = meta[key]!!.split(',').map { it.toFloat() }.toFloatArray()
            val erb = floats("erb_norm_init"); val spec = floats("spec_norm_init")
            require(erb.size == meta["erb_norm_state_size"]!!.toInt() && spec.size == meta["spec_norm_state_size"]!!.toInt() && stateSize == 45424) { "Unexpected denoiser state layout" }
            var state = FloatArray(stateSize).also { erb.copyInto(it, 0); spec.copyInto(it, erb.size) }
            val x = FloatArray(DpdfnetDsp.BINS * 2)
            val step = DpdfnetDsp.FrameStep { re, im, oRe, oIm ->
                for (k in 0 until DpdfnetDsp.BINS) { x[2 * k] = re[k]; x[2 * k + 1] = im[k] }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 1, DpdfnetDsp.BINS.toLong(), 2)).use { inp ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(stateSize.toLong())).use { st ->
                        session.run(mapOf("spec" to inp, "state_in" to st)).use { res ->
                            val e = (res.get("spec_e").orElse(null) as? OnnxTensor) ?: error("Missing spec_e output")
                            val s = (res.get("state_out").orElse(null) as? OnnxTensor) ?: error("Missing state_out output")
                            val ef = FloatArray(DpdfnetDsp.BINS * 2); e.floatBuffer.get(ef)
                            val ns = FloatArray(stateSize); s.floatBuffer.get(ns)
                            require(ef.all { it.isFinite() }) { "Non-finite denoiser output" }
                            state = ns
                            for (k in 0 until DpdfnetDsp.BINS) { oRe[k] = ef[2 * k]; oIm[k] = ef[2 * k + 1] }
                        }
                    }
                }
            }
            DpdfnetDsp.run(samples, step, isCancelled) { d, t -> onProgress(d.toFloat() / t) }
        } }
    }
}
