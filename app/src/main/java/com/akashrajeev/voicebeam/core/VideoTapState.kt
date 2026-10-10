package com.akashrajeev.voicebeam.core

/**
 * Pure state of the reference-interval step on the Video tab. Typed input always wins over a face proposal; a WEAK proposal (no other face tracked) is only a suggestion the
 * user must confirm; a STRONG one is pre-filled but still shown for confirmation. Nothing here starts work: [canStart] only says the interval is valid to hand to the import.
 */
object VideoTapState {
    enum class Source { NONE, PROPOSED, TYPED }
    class State(
        val interval: FootageAnalysis.Interval?, val source: Source, val strength: TapProposal.Strength?,
        val needsConfirm: Boolean, val canStart: Boolean, val message: String
    )

    fun resolve(proposal: TapProposal.Proposal?, typed: FootageAnalysis.Interval?, durationSec: Float, userConfirmedProposal: Boolean = false): State {
        require(durationSec.isFinite() && durationSec > 0f) { "bad duration" }
        if (typed != null) {
            val s = typed.startSec; val e = typed.endSec
            if (!s.isFinite() || !e.isFinite() || s < 0f || e <= s || e > durationSec)
                return State(null, Source.TYPED, null, false, false, "That moment is outside the video. Pick a window inside 0 to ${"%.1f".format(durationSec)} seconds.")
            if (e - s < TapProposal.MIN_SEC)
                return State(typed, Source.TYPED, null, false, false, FootageUiText.reasonText(AbstainReason.REFERENCE_TOO_SHORT))
            if (e - s > TapProposal.MAX_SEC)
                return State(typed, Source.TYPED, null, false, false, "Pick at most ${TapProposal.MAX_SEC.toInt()} seconds.")
            return State(typed, Source.TYPED, null, false, true, "Using your moment ${"%.1f".format(s)}-${"%.1f".format(e)} s.")
        }
        if (proposal == null) return State(null, Source.NONE, null, false, false, FootageUiText.SUGGESTED_TAP_HINT)
        val iv = proposal.interval
        val range = "${"%.1f".format(iv.startSec)}-${"%.1f".format(iv.endSec)} s"
        return when (proposal.strength) {
            TapProposal.Strength.STRONG ->
                State(iv, Source.PROPOSED, proposal.strength, !userConfirmedProposal, userConfirmedProposal,
                    "Suggested moment $range: your person is speaking and the others on screen are quiet. Confirm to use it.")
            TapProposal.Strength.WEAK ->
                State(iv, Source.PROPOSED, proposal.strength, !userConfirmedProposal, userConfirmedProposal,
                    "Suggested moment $range: your person is speaking, but someone off screen could be too. Listen to it, then confirm or change it.")
        }
    }
}
