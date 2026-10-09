package com.akashrajeev.voicebeam.core

/** Detection eligibility is independent of the audio chosen for hearing. */
data class SpeechDecision(val inputs: GateInputs, val queryWeight: Float?)

object SpeechObservation {
    fun observe(enrolling: Boolean, rawSpeech: Boolean, rawRms: Float, i: GateInputs): SpeechDecision =
        SpeechDecision(i.copy(voiceActive = rawSpeech), when {
            enrolling -> 1f
            queryEligible(false, rawSpeech, rawRms, i) -> if (i.hasLock && i.othersSpeaking < .3f) i.lockedSpeaking else 0f
            else -> null
        })

    fun enqueue(decision: SpeechDecision, raw: FloatArray, queue: DropOldestQueue<Pair<FloatArray, Float>>) {
        decision.queryWeight?.let { queue.offer(Pair(raw.copyOf(), it)) }
    }

    fun queryEligible(enrolling: Boolean, rawSpeech: Boolean, rawRms: Float, i: GateInputs): Boolean =
        enrolling || rawSpeech || (i.hasLock && i.lockedVisible && i.lockedSpeaking > .55f &&
            i.othersSpeaking < .3f && rawRms.isFinite() && rawRms >= .005f)

    /** Source time, never callback receipt time, decides whether vision is fresh. */
    fun visionFresh(nowMs: Long, sourceMs: Long): Boolean = sourceMs >= 0 && nowMs - sourceMs in 0..399
}
