package com.akashrajeev.voicebeam.core

/** Captions never receive gated/boosted hearing output. Empty DPDFNet warmup is skipped. */
object CaptionSource {
    fun samples(raw: FloatArray, denoised: FloatArray, useDenoised: Boolean): FloatArray? =
        if (!useDenoised) raw.copyOf() else denoised.takeIf { it.isNotEmpty() }?.copyOf()
}
