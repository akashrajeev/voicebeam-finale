package com.akashrajeev.voicebeam.cloud

/**
 * Decides whether a clip may go to the cloud, and counts the minutes used.
 * Any refusal or failure comes back as a Fallback: the caller keeps the on-device clean audio.
 */
class CloudCleanService(
    private val cleaner: AudioCleaner,
    private val ledger: UsageLedger,
    private val hasKey: () -> Boolean,
) {
    fun run(enabled: Boolean, capMinutes: Int, pcm: ByteArray): CleanResult {
        if (!enabled) return CleanResult.Fallback(FallbackReason.DISABLED)
        if (!hasKey()) return CleanResult.Fallback(FallbackReason.NO_KEY)
        val seconds = ElevenLabsCleaner.billedSeconds(pcm.size)
        if (pcm.size < 16_000) return CleanResult.Fallback(FallbackReason.TOO_SHORT)   // under 0.5 s
        if (seconds > MAX_CLIP_SECONDS) return CleanResult.Fallback(FallbackReason.TOO_LONG)
        if (seconds > ledger.remainingSeconds(capMinutes)) return CleanResult.Fallback(FallbackReason.OVER_CAP)
        val r = cleaner.clean(pcm)
        if (r is CleanResult.Cleaned) ledger.record(r.billedSeconds)
        return r
    }

    companion object {
        const val MAX_CLIP_SECONDS = 300
        const val DEFAULT_CAP_MINUTES = 8
    }
}
