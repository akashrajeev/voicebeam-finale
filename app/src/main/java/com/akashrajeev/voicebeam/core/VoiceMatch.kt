package com.akashrajeev.voicebeam.core

import kotlin.math.sqrt

object VoiceMatch {
    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0f || nb == 0f) return 0f
        return dot / (sqrt(na) * sqrt(nb))
    }

    /** Map raw cosine similarity to a 0..1 "same person" score. */
    fun score(similarity: Float, low: Float = 0.25f, high: Float = 0.60f): Float =
        ((similarity - low) / (high - low)).coerceIn(0f, 1f)

    fun average(list: List<FloatArray>): FloatArray {
        val out = FloatArray(list.first().size)
        for (v in list) for (i in v.indices) out[i] += v[i] / list.size
        return out
    }
}
