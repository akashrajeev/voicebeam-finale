package com.akashrajeev.voicebeam.core

/** Replace DEFAULT as one profile: package that asset and calibrate its own remap. */
data class SpeakerProfile(val asset: String, val cosineLow: Float, val cosineHigh: Float) {
    init { require(asset.startsWith("models/") && cosineLow.isFinite() && cosineHigh.isFinite() && cosineHigh > cosineLow) }
    fun score(cosine: Float) = VoiceMatch.score(cosine, cosineLow, cosineHigh)
    companion object { val DEFAULT = SpeakerProfile("models/eres2net_base.onnx", .25f, .55f) }
}
