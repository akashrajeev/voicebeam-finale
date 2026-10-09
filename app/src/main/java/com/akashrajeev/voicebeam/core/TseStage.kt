package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.separation.PassthroughExtractor
import com.akashrajeev.voicebeam.separation.TargetExtractor

/**
 * Disabled-by-default extraction stage in front of the hearing path.
 *
 * Pure Kotlin (no Android dependencies) so the contract is unit-testable:
 *  - disabled -> returns the input array untouched (zero behavior change);
 *  - enabled -> delegates to [extractor], falling back to the input on any
 *    throw or length breach, and counting fallbacks for diagnostics.
 *
 * Detection/identity paths (VAD, voice fingerprint, captions) never go through
 * here; only the earphone feed does.
 */
class TseStage(
    @Volatile var enabled: Boolean = false,
    @Volatile var extractor: TargetExtractor = PassthroughExtractor(),
) {
    @Volatile var lastUs: Long = 0L
        private set
    @Volatile var fallbackCount: Long = 0L
        private set

    fun apply(frame: FloatArray, cue: TargetExtractor.Cue): FloatArray {
        if (!enabled) return frame
        val t0 = System.nanoTime()
        return try {
            val out = extractor.process(frame, cue)
            lastUs = (System.nanoTime() - t0) / 1000
            if (out.size != frame.size) {
                fallbackCount++
                frame
            } else {
                out
            }
        } catch (_: Throwable) {
            lastUs = (System.nanoTime() - t0) / 1000
            fallbackCount++
            frame
        }
    }

    fun reset() {
        lastUs = 0L
        try {
            extractor.reset()
        } catch (_: Throwable) {
        }
    }
}
