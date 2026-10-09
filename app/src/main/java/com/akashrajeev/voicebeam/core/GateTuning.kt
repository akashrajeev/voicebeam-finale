package com.akashrajeev.voicebeam.core

/** Immutable, sanitized runtime controls. Defaults preserve existing direct gate callers. */
data class GateTuning(
    val strictEnabled: Boolean = false,
    val residualGain: Float = .2f,
    val hangoverMs: Float = 700f,
    val targetThreshold: Float = .8f,
    val releaseMs: Float = 250f,
) {
    fun sanitized() = copy(
        residualGain = if (residualGain.isFinite()) residualGain.coerceIn(.02f, 1f) else .2f,
        hangoverMs = if (hangoverMs.isFinite()) hangoverMs.coerceIn(0f, 2000f) else 700f,
        targetThreshold = if (targetThreshold.isFinite()) targetThreshold.coerceIn(.2f, .99f) else .8f,
        releaseMs = if (releaseMs.isFinite()) releaseMs.coerceIn(60f, 1000f) else 250f)
}
