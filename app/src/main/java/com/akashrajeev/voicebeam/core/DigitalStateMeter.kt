package com.akashrajeev.voicebeam.core

import kotlin.math.log10

/** Sparse sampled digital level ratio, not acoustic attenuation or separation. */
data class DigitalStateReading(val gate: TargetState, val count: Int, val lastSampleMs: Long?, val ratioDb: Double?)
data class DigitalMeterSnapshot(val readings: List<DigitalStateReading> = emptyList())

/** Session-local, caller serialized. Only authoritative snapshot gate values enter buckets. */
class DigitalStateMeter {
    companion object {
        const val WINDOW_MS = 15000L
        const val MIN_SAMPLES = 3
        const val MIN_RAW_RMS = 1e-5f
        const val SAMPLE_INTERVAL_MS = 1000L
        val STATES = listOf(TargetState.TARGET, TargetState.OTHER, TargetState.UNCERTAIN)
    }
    private val samples = ArrayDeque<ProofTelemetry>()
    private var lastAcceptedMs: Long? = null
    fun reset() { samples.clear(); lastAcceptedMs = null }
    fun accept(t: ProofTelemetry): DigitalMeterSnapshot {
        val prior = lastAcceptedMs
        if (prior != null && t.sampledAtMs < prior) reset() // monotonic-clock reset
        if (lastAcceptedMs == null || t.sampledAtMs - lastAcceptedMs!! >= SAMPLE_INTERVAL_MS) {
            lastAcceptedMs = t.sampledAtMs
            if (t.gate in STATES && t.rawRms.isFinite() && t.outputRms.isFinite() &&
                t.rawRms >= MIN_RAW_RMS && t.outputRms >= 0f) samples.addLast(t)
        }
        while (samples.isNotEmpty() && t.sampledAtMs - samples.first().sampledAtMs > WINDOW_MS) samples.removeFirst()
        return DigitalMeterSnapshot(STATES.map { state ->
            val bucket = samples.filter { it.gate == state }
            val raw = bucket.sumOf { it.rawRms.toDouble() * it.rawRms }
            val out = bucket.sumOf { it.outputRms.toDouble() * it.outputRms }
            // Exact zero output has no finite ratio; never invent a display floor.
            val db = if (bucket.size >= MIN_SAMPLES && raw > 0 && out > 0) 10 * log10(out / raw) else null
            DigitalStateReading(state, bucket.size, bucket.lastOrNull()?.sampledAtMs, db)
        })
    }
}
