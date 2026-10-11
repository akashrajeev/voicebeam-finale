package com.akashrajeev.voicebeam.core

import java.util.Locale

/**
 * What the reference-quality guard measured, and a message that names the real cause.
 * Display only: the pass/fail rule is unchanged ([ExtractionSafety.referenceTrusted]);
 * the constants below must mirror it (a test pins that).
 */
class ReferenceGuardReport(
    val seconds: Float,
    /** Fraction of the reference the VAD calls speech; NaN when not measured. */
    val coverage: Float,
    /** Cosine between embeddings of the first and second half of the reference; NaN when not measured. */
    val splitCosine: Float,
    val embedFailed: Boolean = false,
) {
    enum class Cause { TOO_SHORT, EMBED_FAILED, LOW_SPEECH, MIXED_OR_UNSTEADY, OK }

    val cause: Cause get() = when {
        seconds < MIN_SECONDS -> Cause.TOO_SHORT
        embedFailed -> Cause.EMBED_FAILED
        !(coverage >= MIN_COVERAGE) -> Cause.LOW_SPEECH
        !(splitCosine >= MIN_SPLIT_COSINE) -> Cause.MIXED_OR_UNSTEADY
        else -> Cause.OK
    }
    val trusted: Boolean get() = cause == Cause.OK

    /** One line for diag strings and logs. */
    fun diagLine(): String = String.format(
        Locale.US, "refguard: %.1fs, speech %s, voice-consistency %s (needs >=%.0f%% and >=%.2f); ",
        seconds, pct(coverage), num(splitCosine), MIN_COVERAGE * 100, MIN_SPLIT_COSINE
    )

    /** User-facing text for a failed check. Says what was measured and what to do; never claims more than the numbers show. */
    fun userMessage(): String {
        val s = String.format(Locale.US, "%.1f", seconds)
        val hint = "Try 5-8 seconds where only one person is talking."
        return when (cause) {
            Cause.OK -> "Reference window passed."
            Cause.TOO_SHORT -> "Reference check failed: the window is $s s, and it needs at least ${MIN_SECONDS.toInt()} s. $hint"
            Cause.EMBED_FAILED -> "Reference check failed: the voice could not be measured in this $s s window. $hint"
            Cause.LOW_SPEECH -> "Reference check failed: only ${pct(coverage)} of this $s s window is speech (needs ${(MIN_COVERAGE * 100).toInt()}%). Pauses or noise dominate. $hint"
            Cause.MIXED_OR_UNSTEADY -> "Reference check failed: the voice is not consistent across this $s s window (${num(splitCosine)}, needs ${num(MIN_SPLIT_COSINE)}). More than one voice, or a very short window. $hint"
        }
    }

    private fun pct(v: Float) = if (v.isFinite()) "${Math.round(v * 100)}%" else "n/a"
    private fun num(v: Float) = if (v.isFinite()) String.format(Locale.US, "%.2f", v) else "n/a"

    companion object {
        /** Mirror ExtractionSafety.referenceTrusted and OfflineQualityGuard.reference (size < 32000 samples = 2 s). */
        const val MIN_SECONDS = 2f
        const val MIN_COVERAGE = 0.5f
        const val MIN_SPLIT_COSINE = 0.45f
    }
}
