package com.akashrajeev.voicebeam.core

/** Detection eligibility is independent of the audio chosen for hearing. */
object SpeechObservation {
    fun queryEligible(enrolling: Boolean, rawSpeech: Boolean, rawRms: Float, i: GateInputs): Boolean =
        enrolling || rawSpeech || (i.hasLock && i.lockedVisible && i.lockedSpeaking > .55f &&
            i.othersSpeaking < .3f && rawRms.isFinite() && rawRms >= .005f)

    /** Source time, never callback receipt time, decides whether vision is fresh. */
    fun visionFresh(nowMs: Long, sourceMs: Long): Boolean = sourceMs >= 0 && nowMs - sourceMs in 0..399
}
