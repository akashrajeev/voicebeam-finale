package com.akashrajeev.voicebeam.core

/**
 * Pure, display-only copy for the video demo flow (no behaviour, no thresholds). Wording is honest: output is an enhanced derivative, the original is always kept,
 * nothing is claimed about quality beyond what the gates check.
 */
object FootageUiText {
    const val SUGGESTED_TAP_HINT = "Pick a few seconds where ONLY your target speaks (3-10 s), e.g. 2.5 to 6.5."
    val STEPS = listOf(
        "1. Choose a video with two or more people talking.",
        "2. Enter a moment where only the person you want is speaking (3-10 seconds).",
        "3. Tap Isolate offline. Everything runs on this phone, no internet.",
        "4. Compare with the original. If the result can't be trusted, the original is kept and we say why."
    )
    private val STAGE_LABELS = mapOf(
        "COPY" to "Copying the video", "DECODE" to "Reading the audio", "VAD" to "Finding speech", "DENOISE" to "Cleaning background noise",
        "EMBED" to "Learning who is who", "EXTRACT" to "Extracting your target's voice", "RENDER" to "Rebuilding the audio", "MUX" to "Saving the video"
    )
    /** Progress label for a FootageStage name; unknown names fall back to a neutral label. */
    fun stageLabel(stageName: String): String = STAGE_LABELS[stageName] ?: "Working"

    /** Short plain reason the routed path could not be trusted / needs a better reference. */
    fun reasonText(r: AbstainReason): String = when (r) {
        AbstainReason.NO_CLUSTER -> "Couldn't find enough clear speech."
        AbstainReason.SINGLE_CLUSTER -> "Only one voice was found, so there is nothing to separate."
        AbstainReason.TAP_REQUIRED -> "Choose a moment where only your target speaks."
        AbstainReason.TAP_NOT_IN_CLUSTER -> "That moment doesn't clearly match one voice. Pick a cleaner solo moment."
        AbstainReason.REFERENCE_TOO_SHORT -> "That moment is too short. Pick 3-10 seconds of one voice."
        AbstainReason.LIP_AMBIGUOUS -> "Couldn't tell who is speaking from the face."
        AbstainReason.GUARD_REJECTED -> "The result didn't pass the quality checks."
        AbstainReason.PLAN_UNSTABLE -> "The voices were too similar to separate reliably."
        AbstainReason.EVIDENCE_CONTRADICTS_PLAN -> "The extraction contradicted the speaker plan."
    }

    const val ORIGINAL_KEPT = "Original kept - isolation not trusted."
}
