package com.akashrajeev.voicebeam.core

import kotlin.math.abs
import kotlin.math.exp

/** Sample-linear gate/boost transitions; peak limiter attacks now and releases over100ms. */
class ListenEnvelope(sampleRate: Int = 16000) {
    private var previousGain = 1f
    private var previousBoost = 1f
    private var limiter = 1f
    private val release = exp(-1.0 / (sampleRate * .1)).toFloat()
    fun process(clean: FloatArray, n: Int, wantedGain: Float, wantedBoost: Float,
                gated: FloatArray, out: FloatArray): Float {
        var energy = 0f
        var peak = 0f
        for (k in 0 until n) {
            val fraction = (k + 1f) / n
            val gain = previousGain + (wantedGain - previousGain) * fraction
            val boost = previousBoost + (wantedBoost - previousBoost) * fraction
            gated[k] = clean[k] * gain
            out[k] = gated[k] * boost
            peak = maxOf(peak, abs(out[k])); energy += clean[k] * clean[k]
        }
        val ceilingGain = if (peak > .95f) .95f / peak else 1f
        for (k in 0 until n) {
            limiter = if (ceilingGain < limiter) ceilingGain else
                ceilingGain + (limiter - ceilingGain) * release
            out[k] = (out[k] * limiter).coerceIn(-.95f,.95f)
        }
        previousGain = wantedGain; previousBoost = wantedBoost
        return energy
    }
}
