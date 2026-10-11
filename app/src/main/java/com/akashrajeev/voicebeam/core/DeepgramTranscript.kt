package com.akashrajeev.voicebeam.core

/**
 * Turns Deepgram live results into the (text, ended) pair the caption assembler expects.
 * Pure logic, thread safe: the socket thread writes, the caption thread polls.
 */
class DeepgramTranscript {
    private val finals = StringBuilder()
    private var interim = ""
    private var pendingEnd = false

    @Synchronized fun onResult(transcript: String, isFinal: Boolean, speechFinal: Boolean) {
        val t = transcript.trim()
        if (isFinal) {
            if (t.isNotEmpty()) { if (finals.isNotEmpty()) finals.append(' '); finals.append(t) }
            interim = ""
            if (speechFinal) pendingEnd = true
        } else {
            interim = t
        }
    }

    /** Deepgram UtteranceEnd message: close the sentence even if no speech_final arrived. */
    @Synchronized fun onUtteranceEnd() { if (finals.isNotEmpty()) pendingEnd = true }

    /** Current caption text and whether the utterance just ended. An ended poll clears the state. */
    @Synchronized fun poll(): Pair<String, Boolean> {
        if (pendingEnd) {
            val text = finals.toString()
            finals.setLength(0); interim = ""; pendingEnd = false
            return Pair(text, true)
        }
        val text = if (interim.isEmpty()) finals.toString() else if (finals.isEmpty()) interim else "$finals $interim"
        return Pair(text, false)
    }

    @Synchronized fun reset() { finals.setLength(0); interim = ""; pendingEnd = false }
}
