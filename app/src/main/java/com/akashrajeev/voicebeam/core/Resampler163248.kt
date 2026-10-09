package com.akashrajeev.voicebeam.core

import kotlin.math.*

/**
 * Fixed 3x rate converter with 480-sample 48 kHz framing.
 * Takes 256-sample 16 kHz frames → upsamples to 48 kHz → feeds
 * [process] chunks of 480 samples → downsamples result → returns
 * 256-sample 16 kHz frames. No future input; strictly causal.
 *
 * FIR: 95-tap windowed-sinc (Hamming), cutoff 7.6 kHz, DC gain 1.0.
 * Total group delay: ~1.958 ms.
 */
class Resampler163248 {

    // 95-tap windowed-sinc anti-image/anti-alias FIR at 48 kHz, cutoff 7.6 kHz.
    private val taps = DoubleArray(95) { k ->
        val m = k - 47; val f = 7600.0 / 48000
        (if (m == 0) 2 * f else sin(2 * PI * f * m) / (PI * m)) *
            (.54 - .46 * cos(2 * PI * k / 94))
    }.also { a ->
        val sum = a.sum()
        for (k in a.indices) a[k] /= sum
    }

    private val upFilter = Fir(taps)
    private val downFilter = Fir(taps)
    private val chunk48k = FloatArray(480)
    private var fill = 0
    private var decimationPhase = 0

    // Output buffer: holds downsampled 16 kHz samples until enough for one frame.
    // Pre-filled with 256 zeros for startup alignment (group delay compensation).
    private val output16k = ArrayDeque<Float>().also { repeat(256) { _ -> it.addLast(0f) } }

    /**
     * Process one 256-sample 16 kHz frame through the rate converter.
     * The [modelFn] lambda receives 480-sample 48 kHz chunks.
     * Returns exactly 256 samples at 16 kHz.
     */
    fun process(input: FloatArray, modelFn: (FloatArray) -> FloatArray): FloatArray {
        require(input.size == 256 && input.all { it.isFinite() }) {
            "Resampler163248 expects exactly 256 finite samples, got ${input.size}"
        }

        // 3x zero-stuff + anti-image FIR → 768 samples at 48 kHz.
        for (v in input) {
            for (phase in 0..2) {
                val x = if (phase == 0) v * 3f else 0f
                chunk48k[fill++] = upFilter.sample(x)
                if (fill == 480) {
                    // Full chunk: run model at 48 kHz.
                    val result = modelFn(chunk48k)
                    require(result.size == 480 && result.all { it.isFinite() }) {
                        "Model output must be 480 finite samples, got ${result.size}"
                    }
                    // 3x decimation with anti-alias FIR → 160 samples at 16 kHz.
                    for (y in result) {
                        val z = downFilter.sample(y)
                        if (decimationPhase++ % 3 == 0) {
                            output16k.addLast(z)
                        }
                    }
                    fill = 0
                }
            }
        }

        require(output16k.size >= 256 && output16k.size <= 576) {
            "Output buffer size ${output16k.size} out of expected range [256, 576]"
        }
        return FloatArray(256) { output16k.removeFirst() }
    }

    /** Reset all internal state (new session, new lock). */
    fun reset() {
        upFilter.reset()
        downFilter.reset()
        fill = 0
        decimationPhase = 0
        output16k.clear()
        repeat(256) { output16k.addLast(0f) }
    }

    /** Single-tap FIR with circular delay line. */
    private class Fir(private val taps: DoubleArray) {
        private val delay = DoubleArray(taps.size)
        private var cursor = 0

        fun sample(x: Float): Float {
            delay[cursor] = x.toDouble()
            var y = 0.0
            for (k in taps.indices) {
                y += taps[k] * delay[(cursor - k + delay.size) % delay.size]
            }
            cursor = (cursor + 1) % delay.size
            return y.toFloat()
        }

        fun reset() {
            delay.fill(0.0)
            cursor = 0
        }
    }
}