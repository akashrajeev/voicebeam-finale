package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import kotlin.math.max
import kotlin.math.min

/** FLOOR = old behaviour (OTHER/NONE ducked, NONE at 0.04 x source). HARD_MUTE = OTHER_ONLY and NONE bins become true silence. User ear verdict 2026-10-11: non-target must be completely gone. */
enum class SilenceMode { FLOOR, HARD_MUTE, DUCK }

/** Pure helpers for the "only the target is heard" render. 16 kHz mono float. */
object SilenceRender {
    /** DUCK mode: OTHER_ONLY bins are replaced by [bed] (denoised or source audio) at this gain: -26 dB, "as low as background voices", never digital silence. */
    const val DUCK_GAIN = 0.05f

    /**
     * Per-sample: outside ducked bins [audio] is kept; inside them the output is [bed] x [gain]. Linear [fadeSec] crossfades on the kept side of every boundary
     * (same shape as [hardMute]) so a duck neither clicks nor leaks the unducked voice. Pure function of the labels, so the voice returns when a label does.
     */
    fun duck(
        audio: FloatArray, bed: FloatArray, labels: Array<Seg>, sampleRate: Int, ducked: Set<Seg> = setOf(Seg.OTHER_ONLY),
        gain: Float = DUCK_GAIN, fadeSec: Float = 0.015f
    ): FloatArray {
        val binLen = (FootageAnalysis.BIN_SEC * sampleRate).toInt()
        require(binLen > 0 && labels.isNotEmpty() && labels.size * binLen >= audio.size && bed.size == audio.size) { "labels/bed do not cover the audio" }
        require(gain.isFinite() && gain in 0f..1f && fadeSec.isFinite() && fadeSec in 0.01f..0.25f) { "bad duck parameters" }
        val n = audio.size; val fade = max(1, (fadeSec * sampleRate).toInt())
        fun isDucked(i: Int) = labels[min(labels.size - 1, i / binLen)] in ducked
        val out = FloatArray(n)
        for (i in 0 until n) {
            if (isDucked(i)) { out[i] = bed[i] * gain; continue }
            var best = Int.MAX_VALUE
            for (d in 1..fade) {
                if (i + d < n && isDucked(i + d)) { best = d; break }
                if (i - d >= 0 && isDucked(i - d)) { best = d; break }
            }
            val w = if (best == Int.MAX_VALUE) 1f else min(1f, (best - 1).toFloat() / fade)
            out[i] = audio[i] * w + bed[i] * gain * (1f - w)
        }
        return out
    }

    /**
     * Every sample inside a bin whose label is in [muted] becomes exactly 0 (or deterministic noise at [noiseFloorRms] when > 0). Linear fades of [fadeSec] in the
     * NEIGHBOURING kept samples bring the gain to 0 at the boundary, so nothing leaks into a muted bin and there is no click. A pure per-sample function of the
     * labels: when a label returns to TARGET_ONLY, the voice returns.
     */
    fun hardMute(
        audio: FloatArray, labels: Array<Seg>, sampleRate: Int, muted: Set<Seg> = setOf(Seg.OTHER_ONLY, Seg.NONE),
        fadeSec: Float = 0.015f, noiseFloorRms: Float = 0f
    ): FloatArray {
        val binLen = (FootageAnalysis.BIN_SEC * sampleRate).toInt()
        require(binLen > 0 && labels.isNotEmpty() && labels.size * binLen >= audio.size) { "labels do not cover the audio" }
        require(fadeSec.isFinite() && fadeSec >= 0.01f && fadeSec <= 0.25f) { "fade must be 10-250 ms" }
        require(noiseFloorRms.isFinite() && noiseFloorRms >= 0f && noiseFloorRms <= 0.01f) { "bad noise floor" }
        val n = audio.size; val fade = max(1, (fadeSec * sampleRate).toInt())
        fun isMuted(i: Int) = labels[min(labels.size - 1, i / binLen)] in muted
        val amp = noiseFloorRms * 1.7320508f
        var seed = 12345L
        val out = FloatArray(n)
        for (i in 0 until n) {
            if (isMuted(i)) {
                if (amp > 0f) { seed = (seed * 6364136223846793005L + 1442695040888963407L); out[i] = amp * (((seed ushr 40).toInt() and 0xFFFF) / 32767.5f - 1f) }
                continue
            }
            var best = Int.MAX_VALUE
            for (d in 1..fade) {
                if (i + d < n && isMuted(i + d)) { best = d; break }
                if (i - d >= 0 && isMuted(i - d)) { best = d; break }
            }
            out[i] = audio[i] * (if (best == Int.MAX_VALUE) 1f else min(1f, (best - 1).toFloat() / fade))
        }
        return out
    }

    /**
     * Denoisers take a little level off speech (measured: -1.5 dB median on speech frames, -2.2 dB overall retention on the user's clip, against a -3 dB gate).
     * Gain that restores the DENOISED speech-frame RMS to the source's, over VAD-speech frames only, clamped to [1, MAX_MAKEUP]: never attenuates, never boosts
     * more than +3 dB, never chases noise (non-speech frames are ignored). Returns 1 when there is no usable speech.
     */
    const val MAX_MAKEUP = 1.41f   // +3 dB
    fun makeupGain(source: FloatArray, denoised: FloatArray, speech: BooleanArray, frame: Int = 512): Float {
        require(source.size == denoised.size) { "length mismatch" }
        var s = 0.0; var d = 0.0; var n = 0
        var f = 0
        while (f * frame + frame <= source.size) {
            val idx = f
            if (idx < speech.size && speech[idx]) {
                val a = f * frame
                for (i in a until a + frame) { s += source[i].toDouble() * source[i]; d += denoised[i].toDouble() * denoised[i] }
                n++
            }
            f++
        }
        if (n < 20 || d <= 1e-12 || s <= 1e-12) return 1f
        return Math.sqrt(s / d).toFloat().coerceIn(1f, MAX_MAKEUP)
    }

    fun scale(x: FloatArray, g: Float): FloatArray = if (g == 1f) x else FloatArray(x.size) { x[it] * g }
}
