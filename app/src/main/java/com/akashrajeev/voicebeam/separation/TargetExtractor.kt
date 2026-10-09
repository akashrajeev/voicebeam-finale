package com.akashrajeev.voicebeam.separation

/**
 * Seam for target-speaker extraction: given mixed mic audio and what we know about the locked
 * person, return audio that contains mostly that person.
 *
 * Contract (every implementation must keep it):
 *  - Streaming and causal. [process] gets one frame at a time and may keep internal state.
 *  - Output has exactly the same number of samples as the input.
 *  - Total delay is reported in [latencyMs] and must stay inside the live budget.
 *  - [process] must never throw on bad input; it returns the input unchanged when unsure.
 *  - [reset] clears all state (new lock, new session).
 *
 * Nothing in the live pipeline calls this yet. Wiring it in is a separate step, done only after an
 * implementation has passed the phone test in docs/SEPARATION.md.
 */
interface TargetExtractor {
    val name: String
    val latencyMs: Int

    /** What the extractor may use to pick the person. Fields are optional on purpose. */
    data class Cue(
        /** Fixed-size voice fingerprint from enrollment, null when not learned. */
        val voiceEmbedding: FloatArray? = null,
        /** Lip activity 0..1 of the locked face for this frame, null when no face. */
        val lockedLips: Float? = null,
    )

    fun process(frame: FloatArray, cue: Cue): FloatArray
    fun reset()
}

/** Safe default: returns the audio untouched. Used until a real extractor passes the phone test. */
class PassthroughExtractor : TargetExtractor {
    override val name = "passthrough"
    override val latencyMs = 0
    override fun process(frame: FloatArray, cue: TargetExtractor.Cue): FloatArray = frame
    override fun reset() {}
}
