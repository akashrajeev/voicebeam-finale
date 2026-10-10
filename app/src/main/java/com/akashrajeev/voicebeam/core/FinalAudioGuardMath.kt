package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg

/**
 * Pure parts of the selected-bin speaker-retention guard (the embedding calls stay in Android code).
 * KNOWN APPROXIMATION (v1): a 3 s chunk is scored when at least half of it lies in TARGET_ONLY/OVERLAP bins and at least half is speech,
 * on the FINAL rendered audio. OTHER_ONLY parts inside a majority-selected chunk are already ducked by the render, so their cosine
 * contribution is small, but they are not excluded. Scoring concatenated selected-only speech is a post-freeze refinement.
 */
object FinalAudioGuardMath {
    const val MIN_SELECTED = 0.5f
    const val MIN_SPEECH = 0.5f
    const val OUTPUT_MIN_COSINE = 0.4f
    const val HOMOGENEOUS_SOURCE_COSINE = 0.6f
    const val MAX_HOMOGENEOUS_LOSS = 0.15f

    fun selectedFraction(labels: Array<Seg>, a: Int, b: Int, sampleRate: Int = 16000): Float {
        require(a in 0 until b) { "bad chunk" }
        val binLen = (FootageAnalysis.BIN_SEC * sampleRate).toInt()
        require(labels.isNotEmpty() && binLen > 0 && labels.size * binLen >= b) { "labels do not cover the chunk" }
        var sel = 0; var n = 0
        for (i in a until b step 64) {
            val s = labels[i / binLen]
            if (s == Seg.TARGET_ONLY || s == Seg.OVERLAP) sel++
            n++
        }
        return sel.toFloat() / n
    }

    fun speechFraction(speech: BooleanArray, a: Int, b: Int): Float {
        require(a in 0 until b) { "bad chunk" }
        val first = a / 512; val last = minOf(speech.size, (b + 511) / 512)
        require(last > first) { "speech flags do not cover the chunk" }
        return (first until last).count { speech[it] }.toFloat() / (last - first)
    }

    fun chunkQualifies(labels: Array<Seg>, speech: BooleanArray, a: Int, b: Int, sampleRate: Int = 16000) =
        selectedFraction(labels, a, b, sampleRate) >= MIN_SELECTED && speechFraction(speech, a, b) >= MIN_SPEECH

    /** Same thresholds as the exp-10 output guard. Zero scored chunks is a failure (nothing to trust). */
    fun pass(sourceScore: Float, outputScore: Float, chunks: Int): Boolean =
        chunks > 0 && sourceScore.isFinite() && outputScore.isFinite() &&
            outputScore >= OUTPUT_MIN_COSINE &&
            !(sourceScore >= HOMOGENEOUS_SOURCE_COSINE && outputScore < sourceScore - MAX_HOMOGENEOUS_LOSS)
}
