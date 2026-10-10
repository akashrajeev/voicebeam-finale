package com.akashrajeev.voicebeam.cloud

/** Why a clip was not cleaned by the cloud cleaner. The caller keeps the on-device clean audio. */
enum class FallbackReason(val message: String) {
    DISABLED("Cloud clean is off."),
    NO_KEY("No ElevenLabs key saved."),
    TOO_SHORT("Clip is too short to clean."),
    TOO_LONG("Clip is too long for one cloud clean."),
    OVER_CAP("Monthly cloud minutes used up."),
    OFFLINE("No internet. Kept the on-device clean audio."),
    KEY_REJECTED("ElevenLabs rejected the key."),
    QUOTA_EXHAUSTED("ElevenLabs free credits used up."),
    SERVER_ERROR("ElevenLabs had an error. Kept the on-device clean audio."),
}

sealed class CleanResult {
    /** [audio] is an encoded file (for example MP3). [billedSeconds] is what we count against the cap. */
    class Cleaned(val audio: ByteArray, val extension: String, val billedSeconds: Int) : CleanResult()
    class Fallback(val reason: FallbackReason) : CleanResult()
}

/**
 * One way to clean a finished clip. Swappable: the on-device cleaners stay as they are,
 * this is only the optional cloud step.
 */
interface AudioCleaner {
    val id: String
    /** True when the clip leaves the phone. The UI must say so. */
    val sendsAudioOffDevice: Boolean
    /** [pcm] is 16-bit little-endian mono at 16 kHz. Never throws; returns a Fallback instead. */
    fun clean(pcm: ByteArray): CleanResult
}

/** Keeps the audio as it is. Used when nothing else applies. */
class PassthroughCleaner : AudioCleaner {
    override val id = "passthrough"
    override val sendsAudioOffDevice = false
    override fun clean(pcm: ByteArray): CleanResult = CleanResult.Fallback(FallbackReason.DISABLED)
}
