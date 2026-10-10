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
     * Source weights and gain are crossfaded linearly over xfadeSec so segment edges do not click.
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
        val duck = 10f.pow(focusDb / 20f)
        val wO = FloatArray(n); val wD = FloatArray(n); val wE = FloatArray(n); val g = FloatArray(n)
        for (i in 0 until n) {
            val seg = labels[min(labels.size - 1, i / binLen)]
            when (seg) {
                FootageAnalysis.Seg.TARGET_ONLY, FootageAnalysis.Seg.NONE -> { if (denoised != null) wD[i] = 1f else wO[i] = 1f; g[i] = 1f }
                FootageAnalysis.Seg.OTHER_ONLY -> { wO[i] = 1f; g[i] = duck }
                FootageAnalysis.Seg.OVERLAP -> { if (extracted != null) wE[i] = 1f else wO[i] = 1f; g[i] = 1f }
            }
        }
        val k = (xfadeSec * sampleRate).toInt()
        val sO = smooth(wO, k); val sD = smooth(wD, k); val sE = smooth(wE, k); val sG = smooth(g, k)
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = sG[i] * (sO[i] * original[i] + (if (denoised != null) sD[i] * denoised[i] else 0f) + (if (extracted != null) sE[i] * extracted[i] else 0f))
        }
        return out
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
     * Gain ramps linearly from the previous frame's gain, so there are no step discontinuities.
     * floor = 0 reproduces the old hard mute (explicitly allowed, not default).
     */
    fun protect(
        extracted: FloatArray, source: FloatArray, voiced: BooleanArray,
        frame: Int = 512, floor: Float = 0.1f, ctxFrames: Int = 3
    ): FloatArray {
        require(extracted.size == source.size) { "length mismatch" }
        require(frame > 0 && ctxFrames >= 0 && floor.isFinite() && floor in 0f..1f) { "bad protect parameters" }
        val frames = (source.size + frame - 1) / frame
        require(voiced.size >= frames) { "voiced flags do not cover the audio" }
        requireSignal(extracted, "extracted"); requireSignal(source, "source")
        val out = FloatArray(extracted.size)
        var prev = 1f
        for (f in 0 until frames) {
            val a = f * frame; val b = min(source.size, a + frame)
            var se = 0.0; var ss = 0.0
            for (i in a until b) { se += extracted[i].toDouble() * extracted[i]; ss += source[i].toDouble() * source[i] }
            val cap = if (se < 1e-18) 1f else min(1.0, sqrt(ss / se)).toFloat()
            val lo = max(0, f - ctxFrames); val hi = min(frames - 1, f + ctxFrames)
            var v = false
            for (j in lo..hi) if (voiced[j]) { v = true; break }
            val target = if (v) cap else cap * floor
            for (i in a until b) {
                val t = if (b - a > 1) (i - a).toFloat() / (b - a - 1) else 1f
                // ramp may only lower toward the cap, never exceed it
                val gain = min(cap, prev + (target - prev) * t)
                out[i] = extracted[i] * gain
            }
            prev = target
        }
        return out
    }

    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        if (to <= from) return 0f
        var s = 0.0; for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from)).toFloat()
    }

    fun maxStep(x: FloatArray): Float { var m = 0f; for (i in 1 until x.size) m = max(m, abs(x[i] - x[i - 1])); return m }
}
