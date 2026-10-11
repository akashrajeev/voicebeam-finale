package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg

sealed class RenderOutcome {
    /** [audio] is the shipped signal (gates were checked BEFORE the global loudness gain); [gainDb] is that gain, [snappedEdges] the number of mute edges moved to a speech pause. */
    class Rendered(val audio: FloatArray, val gates: OutputGates.Result, val gainDb: Float = 0f, val snappedEdges: Int = 0) : RenderOutcome()
    /** Gates failed: the caller MUST keep the original file ("Original kept - isolation not trusted"). Audio is intentionally not exposed. */
    class Rejected(val gates: OutputGates.Result) : RenderOutcome()
}

/** Pure composition of routed render -> soft-floor protect (NONE bins at 0.04 x source) -> output gates, at 16 kHz mono. */
object FootageRenderPipeline {
    fun run(
        source: FloatArray, extracted: FloatArray, denoised: FloatArray?,
        labels: Array<Seg>, voiced: BooleanArray,
        focusDb: Float = RoutedRender.DEFAULT_FOCUS_DB, sampleRate: Int = 16000,
        silenceMode: SilenceMode = SilenceMode.HARD_MUTE, muteNoiseRms: Float = 0f,
        snapToPauses: Boolean = true, normalizeLoudness: Boolean = false
    ): RenderOutcome {
        val routed = RoutedRender.render(source, denoised, extracted, labels, sampleRate, focusDb)
        val finalAudio = RoutedRender.protect(routed, source, voiced, floor = 0.04f)
        // Sample-exact NONE attenuation (0.04 x source, never digital zero): protect() frames straddle bin edges, so enforce the label boundary after it.
        val floored = RoutedRender.applyNoneMask(finalAudio, source, labels, sampleRate)
        // Default HARD_MUTE: non-target (OTHER_ONLY) and NONE bins are true silence; FLOOR keeps the old ducked behaviour.
        // Mute edges snap to the nearest VAD pause (BoundarySnap) so a turn change does not clip a word; the noise-floor option keeps the plain grid mute.
        var snapped = 0
        val masked = if (silenceMode == SilenceMode.DUCK) SilenceRender.duck(floored, denoised ?: source, labels, sampleRate)
            else if (silenceMode != SilenceMode.HARD_MUTE) floored
            else if (snapToPauses && muteNoiseRms == 0f) {
                val m = BoundarySnap.mask(labels, voiced, source.size, sampleRate); snapped = m.movedEdges
                BoundarySnap.compose(floored, denoised ?: source, m, sampleRate)
            } else SilenceRender.hardMute(floored, labels, sampleRate, noiseFloorRms = muteNoiseRms)
        val gates = OutputGates.check(masked, source, labels, sampleRate)
        if (!gates.passed) return RenderOutcome.Rejected(gates)
        // Global speech-level gain AFTER the gates: the gates are ratio based, so a scalar cannot change their verdict, and measuring them first keeps their absolute floors honest.
        val n = if (normalizeLoudness) Loudness.normalize(masked, voiced, labels, sampleRate) else null
        return RenderOutcome.Rendered(n?.audio ?: masked, gates, n?.gainDb ?: 0f, snapped)
    }
}
