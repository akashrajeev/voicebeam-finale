package com.akashrajeev.voicebeam.core

/**
 * Per-frame audio maths on caller-owned buffers, so the audio loop allocates
 * nothing here. Same arithmetic as the earlier inline loops.
 */
object FrameDsp {
    /**
     * out[k] = mix * denoised[k] + (1 - mix) * input[input.size - n + k] for k in 0 until n.
     * Only for already aligned samples. Streaming GTCRN uses DenoiseAlignment instead.
     */
    fun mixDenoised(denoised: FloatArray, input: FloatArray, mix: Float, out: FloatArray, n: Int) {
        val offset = input.size - n
        for (k in 0 until n) out[k] = mix * denoised[k] + (1f - mix) * input[offset + k]
    }

    /**
     * gated[k] = clean[k] * gain; boosted[k] = clamp(gated[k] * boost, -1, 1).
     * Returns the sum of squares of [clean] (energy before the gate).
     */
    /** Peak-safe hearing boost: reduce the requested boost for this frame instead of hard clipping. */
    fun safeBoost(clean: FloatArray, n: Int, gain: Float, requested: Float): Float {
        var peak = 0f
        for (k in 0 until n) peak = maxOf(peak, kotlin.math.abs(clean[k] * gain))
        return if (peak > 0f) minOf(requested, 0.95f / peak) else requested
    }

    fun gateAndBoost(clean: FloatArray, n: Int, gain: Float, boost: Float, gated: FloatArray, boosted: FloatArray): Float {
        var e = 0f
        for (k in 0 until n) {
            gated[k] = clean[k] * gain
            boosted[k] = (gated[k] * boost).coerceIn(-1f, 1f)
            e += clean[k] * clean[k]
        }
        return e
    }
}
