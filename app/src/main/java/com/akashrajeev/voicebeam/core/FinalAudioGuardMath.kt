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
    // Recalibrated on a 10-case LAPTOP distribution with the TitaNet-small port (data-thin; revisit with device renders).
    // Output cosine: true fails max 0.293 (venue 0.137, others 0.269/0.277/0.293); honest min 0.352; 0.32 sits between with margins +0.032 / -0.027.
    // Rule 2 arms at source cos >= 0.6 and fires on gap s-o > 0.45 (venue 0.529 fires; honest gaps 0.236..0.373 clear). Was 0.4 / 0.6 / 0.15.
    // Reference is NOT bandlimited (tested and disqualified).
    // KNOWN LIMITATION: on very noisy references the guard cannot separate a good fallback (clip2 o=0.277) from a bad render (o=0.269)
    // and ships the original. Safe default. Future fix: denoise the reference before guard embedding.
    const val OUTPUT_MIN_COSINE = 0.32f
    const val HOMOGENEOUS_SOURCE_COSINE = 0.6f
    const val MAX_HOMOGENEOUS_LOSS = 0.45f

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
