package com.akashrajeev.voicebeam.core

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pre-gate limiter for a DENOISED render. OutputGates caps every 512-sample frame, bands 0-1k / 1-2k / 2-4k, at source + 1 dB. A denoiser can put a little
 * energy above the source in a few frames (measured on a real 34 s pitch recording: 3 frames, worst +1.9 dB, which rejected the whole denoised render).
 * This scales exactly those frames down so no capped band exceeds the SOURCE band (0 dB, 1 dB inside the gate's allowance). Frames are the gate's own
 * (aligned at 0, same Hann window), so a per-frame gain g scales band energy by g^2 exactly. It only ever attenuates; it changes no gate threshold.
 */
object BandLimiter {
    class Result(val audio: FloatArray, val limitedFrames: Int, val totalFrames: Int)

    fun limit(source: FloatArray, denoised: FloatArray): Result {
        require(source.size == denoised.size) { "length mismatch" }
        val out = denoised.copyOf()
        val bin = OutputGates.BIN
        val frames = (source.size + bin - 1) / bin
        var limited = 0
        for (f in 0 until frames) {
            val a = f * bin
            val bs = OutputGates.bandEnergy(source, a)
            val bo = OutputGates.bandEnergy(denoised, a)
            var g = 1.0
            for (k in 0 until 3) {
                val lim = max(bs[k], OutputGates.BAND_FLOOR_ENERGY)
                if (bo[k] > lim) g = minOf(g, sqrt(lim / bo[k]))
            }
            if (g < 1.0) {
                limited++
                val end = minOf(source.size, a + bin)
                for (i in a until end) out[i] = (out[i] * g).toFloat()
            }
        }
        return Result(out, limited, frames)
    }
}
