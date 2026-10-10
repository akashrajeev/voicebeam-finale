package com.akashrajeev.voicebeam.engine

import com.akashrajeev.voicebeam.core.CaptionSegment

/**
 * Turns streaming recogniser output into timed caption segments and decides
 * whether each utterance belonged to the locked speaker. Pure logic for testing.
 */
class CaptionAssembler(private val sampleRate: Int = 16000) {
    var fedSamples = 0L
        private set
    val nowMs: Long get() = fedSamples * 1000 / sampleRate

    private var uttStartMs = -1L
    private var targetVotes = 0f
    private var votes = 0
    var partial = ""
        private set

    val partialIsTarget: Boolean get() = votes == 0 || targetVotes / votes >= 0.5f

    /** Call once per audio block after the recogniser ran on it. Returns a finished segment when an utterance ended. */
    fun onBlock(blockSize: Int, text: String, ended: Boolean, targetProbability: Float, speech: Boolean): CaptionSegment? {
        val blockStart = nowMs
        fedSamples += blockSize
        if (text.isNotBlank() && uttStartMs < 0) uttStartMs = maxOf(0L, blockStart - 400)
        if (speech) { targetVotes += targetProbability; votes++ }
        partial = text
        if (!ended) return null
        val seg = if (text.isNotBlank() && uttStartMs >= 0) CaptionSegment(uttStartMs, nowMs, text, partialIsTarget) else null
        uttStartMs = -1; targetVotes = 0f; votes = 0; partial = ""
        return seg
    }

    /** Preserve the capture timeline after dropped queue blocks. */
    fun advanceTo(samplePosition: Long) { if (samplePosition > fedSamples) fedSamples = samplePosition }

    fun reset() { fedSamples = 0; uttStartMs = -1; targetVotes = 0f; votes = 0; partial = "" }
}
