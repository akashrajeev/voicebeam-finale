package com.akashrajeev.voicebeam.core

/** Technical digital snapshots, not acoustic noise-reduction or separation measurements. */
data class ProofTelemetry(
    val sampledAtMs: Long,
    val gate: TargetState,
    val targetProbability: Float,
    val voiceMatch: Float?,
    val rawRms: Float,
    val outputRms: Float,
    val rawVadProbability: Float,
    val cleanVadProbability: Float?,
    val playbackUnderruns: Int?,
    val actualMix: Float,
    val quietOthers: Float,
    val proposedMix: Float,
    val candidateRawFloor: Float?,
)

/** Shadow recommendations only. No method here returns audio, gain, boost or a gate decision. */
class ShadowListeningPolicy {
    private var rawFloor: Float? = null

    fun proposeMix(actual: Float, gate: TargetState, rawSpeech: Boolean, rawRms: Float): Float {
        val safeActual = actual.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        if (!actual.isFinite() || !rawRms.isFinite() || rawRms < 0f) return safeActual
        return when {
            gate == TargetState.OTHER || !rawSpeech -> 1f
            gate == TargetState.TARGET && rawRms >= .02f -> .9f
            else -> safeActual
        }
    }

    /** Once-per-second raw-energy hypothesis. Quiet speech/VAD errors can contaminate it. */
    fun sampleFloor(rawSpeech: Boolean, rawRms: Float): Float? {
        if (!rawSpeech && rawRms.isFinite() && rawRms >= 0f) {
            rawFloor = rawFloor?.let { it + .1f * (rawRms - it) } ?: rawRms
        }
        return rawFloor
    }
}
