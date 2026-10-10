package com.akashrajeev.voicebeam.core

import kotlin.math.exp

/** Speaker-aware dry/wet control, NOT speaker extraction. No detection thresholds change. */
class HearingMix(private val sampleRate: Int = 16000, private val transitionMs: Float = 80f) {
    var effective = 0f
        private set
    var requested = 0f
        private set
    private var initialized = false

    fun target(userMix: Float, i: GateInputs, state: TargetState): Float {
        // Explicit off remains off. Invalid settings fall back to the main default.
        val user = if (userMix.isFinite()) userMix.coerceIn(0f, 1f) else .7f
        if (!i.hasLock || user == 0f) return user
        val floor = when {
            !i.voiceLearned -> .85f
            !i.audioOnly && !i.lockedVisible -> 1f
            state == TargetState.OTHER || state == TargetState.OVERLAP -> 1f
            state == TargetState.TARGET -> .85f
            else -> .95f
        }
        return maxOf(user, floor)
    }

    fun next(userMix: Float, i: GateInputs, state: TargetState, samples: Int): Float {
        requested = target(userMix, i, state)
        if (!initialized) { effective = requested; initialized = true }
        else {
            val alpha = 1f - exp(-samples.coerceAtLeast(0) * 1000f / (sampleRate * transitionMs))
            effective += (requested - effective) * alpha
        }
        return effective
    }
}

/** Technical-only event cadence. Counting and logging never change fallback audio. */
class FallbackCounter(private val intervalMs: Long = 5000) {
    var count = 0L
        private set
    private var reportedAt: Long? = null
    fun record(nowMs: Long): Boolean {
        count++
        val prior = reportedAt
        if (prior == null || nowMs - prior >= intervalMs || nowMs < prior) {
            reportedAt = nowMs
            return true
        }
        return false
    }
}

/** Warmup (empty output) is valid. Partial, oversized or nonfinite output is not. */
object DenoiseOutput {
    fun valid(output: FloatArray, expected: Int): Boolean =
        (output.isEmpty() || output.size == expected) && output.all { it.isFinite() }
}
