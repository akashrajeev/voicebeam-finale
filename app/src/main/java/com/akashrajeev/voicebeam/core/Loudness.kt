package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Speech-level normalisation of the SHIPPED audio (pure JVM). One global scalar, so ratio gates (retention, band cap, none ratio) are invariant to it: apply it AFTER the gates. */
object Loudness {
    const val TARGET_DB = -20f      // speech RMS target, dBFS. PROVISIONAL
    const val MAX_BOOST_DB = 12f
    const val MAX_CUT_DB = -6f
    const val PEAK_CEIL_DB = -1f
    const val MIN_SPEECH_FRAMES = 20

    class Result(val audio: FloatArray, val gainDb: Float, val measuredDb: Float?)

    /** Measures RMS over 512-sample VAD-speech frames inside kept bins (TARGET_ONLY/OVERLAP) with non-zero output; no usable speech -> unchanged. */
    fun normalize(audio: FloatArray, speech: BooleanArray, labels: Array<Seg>, sampleRate: Int = 16000, targetDb: Float = TARGET_DB): Result {
        val binLen = (FootageAnalysis.BIN_SEC * sampleRate).toInt()
        require(binLen > 0 && labels.isNotEmpty() && labels.size * binLen >= audio.size) { "labels do not cover the audio" }
        var sum = 0.0; var cnt = 0L; var frames = 0
        var f = 0
        while ((f + 1) * 512 <= audio.size) {
            val a = f * 512
            val lab = labels[min(labels.size - 1, (a + 256) / binLen)]
            if (f < speech.size && speech[f] && (lab == Seg.TARGET_ONLY || lab == Seg.OVERLAP)) {
                var s = 0.0; for (i in a until a + 512) s += audio[i].toDouble() * audio[i]
                if (s > 1e-9) { sum += s; cnt += 512; frames++ }
            }
            f++
        }
        if (frames < MIN_SPEECH_FRAMES || sum <= 0.0) return Result(audio, 0f, null)
        val measured = (10 * log10(sum / cnt)).toFloat()
        var g = (targetDb - measured).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
        var peak = 0f; for (x in audio) peak = max(peak, abs(x))
        if (peak > 0f) g = min(g, PEAK_CEIL_DB - 20 * log10(peak))          // never clip
        if (abs(g) < 0.25f) return Result(audio, 0f, measured)               // not worth touching
        val k = 10.0.pow(g / 20.0).toFloat()
        return Result(FloatArray(audio.size) { audio[it] * k }, g, measured)
    }
}
