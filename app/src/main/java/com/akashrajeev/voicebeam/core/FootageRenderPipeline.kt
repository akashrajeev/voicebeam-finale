package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg

sealed class RenderOutcome {
    class Rendered(val audio: FloatArray, val gates: OutputGates.Result) : RenderOutcome()
    /** Gates failed: the caller MUST keep the original file ("Original kept - isolation not trusted"). Audio is intentionally not exposed. */
    class Rejected(val gates: OutputGates.Result) : RenderOutcome()
}

/** Pure composition of routed render -> soft-floor protect (NONE bins at 0.04 x source) -> output gates, at 16 kHz mono. */
object FootageRenderPipeline {
    fun run(
        source: FloatArray, extracted: FloatArray, denoised: FloatArray?,
        labels: Array<Seg>, voiced: BooleanArray,
        focusDb: Float = RoutedRender.DEFAULT_FOCUS_DB, sampleRate: Int = 16000
    ): RenderOutcome {
        val routed = RoutedRender.render(source, denoised, extracted, labels, sampleRate, focusDb)
        val finalAudio = RoutedRender.protect(routed, source, voiced, floor = 0.04f)
        // Sample-exact NONE attenuation (0.04 x source, never digital zero): protect() frames straddle bin edges, so enforce the label boundary after it.
        val masked = RoutedRender.applyNoneMask(finalAudio, source, labels, sampleRate)
        val gates = OutputGates.check(masked, source, labels, sampleRate)
        return if (gates.passed) RenderOutcome.Rendered(masked, gates) else RenderOutcome.Rejected(gates)
    }
}
