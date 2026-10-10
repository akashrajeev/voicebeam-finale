package com.akashrajeev.voicebeam.core

/** Unknown and speaker routes still get buffers, but never live microphone samples. */
object MonitorOutput {
    fun samples(enabled: Boolean, actualHeadphoneRoute: Boolean, speech: FloatArray, silence: FloatArray): FloatArray =
        if (enabled && actualHeadphoneRoute) speech else silence
}
