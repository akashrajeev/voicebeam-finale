package com.akashrajeev.voicebeam.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure-JVM DSP for the DPDFNet speech denoiser (16 kHz profile): n_fft = window = 320, hop = 160, Vorbis window, centered STFT with reflect padding,
 * no normalization, torch-style inverse (overlap-add divided by the window-square envelope), then the sherpa-onnx output shift of 2 * n_fft samples
 * (model look-ahead of 4 frames) with zero fill at the end. Matches sherpa-onnx offline DPDFNet (offline-speech-denoiser-dpdfnet-impl.h).
 * The ONNX model itself is injected as a [FrameStep], so the whole pipeline is unit-testable with fake steps.
 */
object DpdfnetDsp {
    const val N = 320
    const val HOP = 160
    const val BINS = N / 2 + 1
    const val SHIFT = 2 * N

    /** Stateful per-frame model step: takes the noisy spectrum frame (re, im), returns the enhanced frame. Called in frame order. */
    fun interface FrameStep { fun process(re: FloatArray, im: FloatArray, outRe: FloatArray, outIm: FloatArray) }

    val window: FloatArray = FloatArray(N) { i ->
        val s = sin(0.5f * PI.toFloat() * (i + 0.5f) / (N / 2f))
        sin(0.5f * PI.toFloat() * s * s)
    }

    private val cosT = DoubleArray(N) { cos(2.0 * PI * it / N) }
    private val sinT = DoubleArray(N) { sin(2.0 * PI * it / N) }

    fun frameCount(nSamples: Int) = 1 + nSamples / HOP

    /** Reflect pad by N/2 on both sides (needs more than N/2 samples). */
    internal fun reflectPad(x: FloatArray): FloatArray {
        val p = N / 2
        require(x.size > p) { "audio too short for the denoiser" }
        val out = FloatArray(x.size + 2 * p)
        x.copyInto(out, p)
        for (i in 1..p) { out[p - i] = x[i]; out[p + x.size - 1 + i] = x[x.size - 1 - i] }
        return out
    }

    /** Windowed real DFT of padded[start until start+N] into (re, im) of BINS values. */
    internal fun dftFrame(padded: FloatArray, start: Int, re: FloatArray, im: FloatArray) {
        val fr = DoubleArray(N) { padded[start + it] * window[it].toDouble() }
        for (k in 0 until BINS) {
            var r = 0.0; var m = 0.0
            for (n in 0 until N) { val idx = (k * n) % N; r += fr[n] * cosT[idx]; m -= fr[n] * sinT[idx] }
            re[k] = r.toFloat(); im[k] = m.toFloat()
        }
    }

    /** Inverse real DFT of one frame (imag of DC and Nyquist ignored), multiplied by the synthesis window. */
    internal fun idftFrame(re: FloatArray, im: FloatArray, out: DoubleArray) {
        for (n in 0 until N) {
            var s = re[0].toDouble() + re[BINS - 1] * (if (n % 2 == 0) 1.0 else -1.0)
            for (k in 1 until BINS - 1) { val idx = (k * n) % N; s += 2.0 * (re[k] * cosT[idx] - im[k] * sinT[idx]) }
            out[n] = s / N * window[n]
        }
    }

    /** Full chain: STFT -> step -> iSTFT -> shift. Output length == input length. Cancel is polled every frame. */
    fun run(
        x: FloatArray, step: FrameStep, isCancelled: () -> Boolean = { false }, onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): FloatArray {
        require(x.all { it.isFinite() }) { "non-finite audio" }
        val padded = reflectPad(x)
        val frames = frameCount(x.size)
        val acc = DoubleArray((frames - 1) * HOP + N)
        val env = DoubleArray(acc.size)
        val re = FloatArray(BINS); val im = FloatArray(BINS); val oRe = FloatArray(BINS); val oIm = FloatArray(BINS)
        val syn = DoubleArray(N)
        for (f in 0 until frames) {
            if (isCancelled()) throw kotlinx.coroutines.CancellationException("Denoise cancelled")
            dftFrame(padded, f * HOP, re, im)
            step.process(re, im, oRe, oIm)
            idftFrame(oRe, oIm, syn)
            val base = f * HOP
            for (n in 0 until N) { acc[base + n] += syn[n]; env[base + n] += window[n].toDouble() * window[n] }
            if (f % 100 == 0) onProgress(f, frames)
        }
        val half = N / 2
        val y = FloatArray(x.size)
        // drop the centre padding, normalise by the window envelope (torch istft semantics), then shift left by SHIFT and zero-fill the tail
        for (i in y.indices) {
            val src = i + SHIFT
            if (src >= x.size) break
            val a = src + half
            y[i] = (acc[a] / max(env[a], 1e-11)).toFloat()
        }
        return y
    }
}
