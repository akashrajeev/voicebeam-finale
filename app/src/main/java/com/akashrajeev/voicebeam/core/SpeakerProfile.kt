package com.akashrajeev.voicebeam.core

/** Replace DEFAULT as one profile: package that asset and calibrate its own remap. */
data class SpeakerProfile(val asset: String, val cosineLow: Float, val cosineHigh: Float) {
    init { require(asset.startsWith("models/") && cosineLow.isFinite() && cosineHigh.isFinite() && cosineHigh > cosineLow) }
    fun score(cosine: Float) = VoiceMatch.score(cosine, cosineLow, cosineHigh)
    companion object { val DEFAULT = SpeakerProfile("models/campplus_advanced.onnx", .35f, .75f) }
}
