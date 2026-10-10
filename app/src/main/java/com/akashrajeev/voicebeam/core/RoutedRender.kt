package com.akashrajeev.voicebeam.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Pure-JVM render math: routed per-segment mixing and soft-floor protect(). No ORT, no Android. */
object RoutedRender {
    const val FOCUS_MIN_DB = -30f
    const val DEFAULT_FOCUS_DB = -18f
    const val DEFAULT_XFADE_SEC = 0.05f

    private fun requireSignal(x: FloatArray, name: String) {
        require(x.all { it.isFinite() }) { "$name contains non-finite samples" }
    }

    /**
     * Routed render. Bins are FootageAnalysis.BIN_SEC (0.5s) long labels over [original].
     * TARGET_ONLY / NONE: lightly denoised audio if provided, else the original, gain 1.
     * OTHER_ONLY: original ducked by focusDb (0 = no duck, floor -30).
     * OVERLAP: [extracted] (target-isolated) when provided; without it the original passes through untouched (never fabricated).
     * Source weights and gain are crossfaded (centered moving average) so the full transition at each edge is about xfadeSec wide.
     * All inputs must be the same length and sample rate. Output length == input length.
     */
    fun render(
        original: FloatArray, denoised: FloatArray?, extracted: FloatArray?,
        labels: Array<FootageAnalysis.Seg>, sampleRate: Int,
        focusDb: Float = DEFAULT_FOCUS_DB, xfadeSec: Float = DEFAULT_XFADE_SEC
    ): FloatArray {
        require(sampleRate >= 8000) { "bad sample rate" }
        require(focusDb.isFinite() && focusDb <= 0f && focusDb >= FOCUS_MIN_DB) { "focusDb must be in [$FOCUS_MIN_DB, 0]" }
        require(xfadeSec.isFinite() && xfadeSec >= 0f && xfadeSec <= 0.25f) { "bad crossfade" }
        require(denoised == null || denoised.size == original.size) { "denoised length mismatch" }
        require(extracted == null || extracted.size == original.size) { "extracted length mismatch" }
        requireSignal(original, "original"); denoised?.let { requireSignal(it, "denoised") }; extracted?.let { requireSignal(it, "extracted") }
        val n = original.size
        val binLen = (FootageAnalysis.BIN_SEC * sampleRate).toInt()
        require(labels.isNotEmpty() && labels.size * binLen >= n) { "labels do not cover the audio" }
        val w = weights(n, denoised != null, extracted != null, labels, sampleRate, focusDb, xfadeSec)
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = w.gain[i] * (w.original[i] * original[i] + (if (denoised != null) w.denoised[i] * denoised[i] else 0f) + (if (extracted != null) w.extracted[i] * extracted[i] else 0f))
        }
        return out
    }

    class Weights(val original: FloatArray, val denoised: FloatArray, val extracted: FloatArray, val gain: FloatArray)

    /** Smoothed source weights and gain per sample. Exposed so tests can assert in weight space (the primary check) rather than on waveforms. */
    internal fun weights(
        n: Int, hasDenoised: Boolean, hasExtracted: Boolean, labels: Array<FootageAnalysis.Seg>, sampleRate: Int,
        focusDb: Float, xfadeSec: Float
    ): Weights {
        val binLen = (FootageAnalysis.BIN_SEC * sampleRate).toInt()
        val duck = 10f.pow(focusDb / 20f)
        val wO = FloatArray(n); val wD = FloatArray(n); val wE = FloatArray(n); val g = FloatArray(n)
        for (i in 0 until n) {
            when (labels[min(labels.size - 1, i / binLen)]) {
                FootageAnalysis.Seg.TARGET_ONLY, FootageAnalysis.Seg.NONE -> { if (hasDenoised) wD[i] = 1f else wO[i] = 1f; g[i] = 1f }
                FootageAnalysis.Seg.OTHER_ONLY -> { wO[i] = 1f; g[i] = duck }
                FootageAnalysis.Seg.OVERLAP -> { if (hasExtracted) wE[i] = 1f else wO[i] = 1f; g[i] = 1f }
            }
        }
        val k = (xfadeSec * sampleRate / 2f).toInt() // centered window 2k+1 ~= xfadeSec total transition width
        return Weights(smooth(wO, k), smooth(wD, k), smooth(wE, k), smooth(g, k))
    }

    /** Centered moving average with window 2k+1 (edges clamped). k = 0 returns the input. */
    internal fun smooth(x: FloatArray, k: Int): FloatArray {
        if (k <= 0 || x.isEmpty()) return x.copyOf()
        val n = x.size; val pre = DoubleArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + x[i]
        return FloatArray(n) { i ->
            val a = max(0, i - k); val b = min(n, i + k + 1)
            ((pre[b] - pre[a]) / (b - a)).toFloat()
        }
    }

    /**
     * Soft-floor protect(). Replaces the hard mute with a floor gain on frames the VAD calls non-speech,
     * and caps every frame so output RMS never exceeds the source RMS.
     * [voiced] is one flag per [frame] samples of the SOURCE (Silero-style). A frame counts as voiced if any frame within +/- ctxFrames is voiced.
     * Gain ramps linearly toward the NEXT frame's target (look-ahead), so there are no step discontinuities and non-voiced frames carry no ramp residue.
     * floor = 0 mutes non-voiced frames but still ramps over the first non-voiced frame (about 32 ms at 16 kHz), unlike the old immediate hard mute. Deliberate: avoids a click.
     */
    /** Default floor 0.04 (about -28 dB): any VAD-false frame outside the hangover is at most 4% of the source, under the 0.05 NONE-bin gate whatever the labels say. */
    fun protect(
        extracted: FloatArray, source: FloatArray, voiced: BooleanArray,
        frame: Int = 512, floor: Float = 0.04f, ctxFrames: Int = 3, hardMute: BooleanArray? = null
    ): FloatArray {
        require(extracted.size == source.size) { "length mismatch" }
        require(frame > 0 && ctxFrames >= 0 && floor.isFinite() && floor in 0f..1f) { "bad protect parameters" }
        val frames = (source.size + frame - 1) / frame
        require(voiced.size >= frames) { "voiced flags do not cover the audio" }
        require(hardMute == null || hardMute.size >= frames) { "hardMute flags do not cover the audio" }
        requireSignal(extracted, "extracted"); requireSignal(source, "source")
        val caps = FloatArray(frames); val targets = FloatArray(frames)
        for (f in 0 until frames) {
            val a = f * frame; val b = min(source.size, a + frame)
            var se = 0.0; var ss = 0.0
            for (i in a until b) { se += extracted[i].toDouble() * extracted[i]; ss += source[i].toDouble() * source[i] }
            val cap = if (se < 1e-18) 1f else min(1.0, sqrt(ss / se)).toFloat()
            val lo = max(0, f - ctxFrames); val hi = min(frames - 1, f + ctxFrames)
            var v = false
            for (j in lo..hi) if (voiced[j]) { v = true; break }
            caps[f] = cap
            // hardMute (NONE-labelled bins) takes precedence over VAD context; other non-voiced frames get the soft floor
            targets[f] = if (hardMute != null && hardMute[f]) 0f else if (v) cap else cap * floor
        }
        // Look-ahead ramp: frame f starts at its OWN target and ramps to frame f+1's target by its end, so the transition happens in the
        // frame BEFORE a non-voiced/NONE frame and the non-voiced frame itself carries no ramp residue. Gain never exceeds the frame cap.
        val out = FloatArray(extracted.size)
        for (f in 0 until frames) {
            val a = f * frame; val b = min(source.size, a + frame)
            val start = targets[f]; val end = if (f + 1 < frames) targets[f + 1] else targets[f]
            for (i in a until b) {
                val t = if (b - a > 1) (i - a).toFloat() / (b - a - 1) else 1f
                out[i] = extracted[i] * min(caps[f], start + (end - start) * t)
            }
        }
        return out
    }

    /**
     * Sample-exact NONE mask: gain 0 inside every NONE bin; linear fades of fadeSec sit in the NEIGHBOURING non-NONE bins
     * (ending exactly at the NONE boundary, or starting exactly where it ends) so nothing leaks into a NONE bin.
     */
    fun applyNoneMask(audio: FloatArray, labels: Array<FootageAnalysis.Seg>, sampleRate: Int, fadeSec: Float = 0.01f): FloatArray {
        val binLen = (FootageAnalysis.BIN_SEC * sampleRate).toInt()
        require(binLen > 0 && labels.isNotEmpty() && labels.size * binLen >= audio.size) { "labels do not cover the audio" }
        require(fadeSec.isFinite() && fadeSec >= 0f && fadeSec <= 0.25f) { "bad fade" }
        val n = audio.size; val fade = max(1, (fadeSec * sampleRate).toInt())
        val g = FloatArray(n) { 1f }
        fun none(i: Int) = labels[min(labels.size - 1, i / binLen)] == FootageAnalysis.Seg.NONE
        for (i in 0 until n) if (none(i)) g[i] = 0f
        for (i in 0 until n) {
            if (none(i)) continue
            // distance (in samples) to the nearest NONE sample within the fade length
            var best = Int.MAX_VALUE
            for (d in 1..fade) {
                if (i + d < n && none(i + d)) { best = d; break }
                if (i - d >= 0 && none(i - d)) { best = d; break }
            }
            if (best != Int.MAX_VALUE) g[i] = min(g[i], (best - 1).toFloat() / fade)
        }
        return FloatArray(n) { audio[it] * g[it] }
    }

    /** One flag per [frame] samples: true when the frame centre lies in a NONE-labelled bin. */
    fun noneFrames(labels: Array<FootageAnalysis.Seg>, nSamples: Int, sampleRate: Int, frame: Int = 512): BooleanArray {
        val binLen = (FootageAnalysis.BIN_SEC * sampleRate).toInt()
        require(binLen > 0 && labels.isNotEmpty() && labels.size * binLen >= nSamples) { "labels do not cover the audio" }
        val frames = (nSamples + frame - 1) / frame
        return BooleanArray(frames) { f ->
            val centre = min(nSamples - 1, f * frame + frame / 2)
            labels[min(labels.size - 1, centre / binLen)] == FootageAnalysis.Seg.NONE
        }
    }

    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        if (to <= from) return 0f
        var s = 0.0; for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from)).toFloat()
    }

    fun maxStep(x: FloatArray): Float { var m = 0f; for (i in 1 until x.size) m = max(m, abs(x[i] - x[i - 1])); return m }
}
