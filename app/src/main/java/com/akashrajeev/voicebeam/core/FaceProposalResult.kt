package com.akashrajeev.voicebeam.core

/**
 * Outcome of the face-tap reference proposal. Three DIFFERENT facts, never one message: a proposal exists, the video was read fine but no moment qualifies,
 * or something failed (audio could not be decoded, VAD/IO error, ...). The UI must not show "no solo moment" for a failure.
 */
sealed class FaceProposalResult {
    class Found(val proposal: TapProposal.Proposal) : FaceProposalResult()
    /** Audio and face evidence were read; no 3-10 s window has this face talking, the others silent and enough speech. */
    object NoWindow : FaceProposalResult()
    class Failed(val reason: String) : FaceProposalResult()

    companion object {
        const val NO_WINDOW_TEXT = "No 3-10 second moment found where only this person speaks. Type one below."
        const val FOUND_TEXT = "Found a suggested moment. Check it, then confirm."

        fun failedText(reason: String): String {
            val r = reason.trim().ifEmpty { "unknown error" }
            return if (r.contains("No audio track found", ignoreCase = true) || r.contains("Choose a video with an audio track", ignoreCase = true))
                "Couldn't read any audio from this video (it may have no sound track, e.g. a muted or GIF-converted share). Type a moment below, or choose the original file."
            else "Couldn't read this video's audio to suggest a moment: $r. Type one below."
        }

        fun userText(r: FaceProposalResult): String = when (r) {
            is Found -> FOUND_TEXT
            NoWindow -> NO_WINDOW_TEXT
            is Failed -> failedText(r.reason)
        }
    }
}
