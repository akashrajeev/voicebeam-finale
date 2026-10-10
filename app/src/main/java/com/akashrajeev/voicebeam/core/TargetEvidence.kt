package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Extractor-health evidence for TARGET_ONLY bins (pure JVM, OFF by default; thresholds PROVISIONAL, calibrated on one clip).
 *
 * Mix-embedding clustering cannot see a short interjection by another speaker inside a target-dominated window, and TARGET_ONLY bins pass the
 * original mix untouched. SpeakerBeam output energy relative to the source ("health" = rms(extracted)/rms(source), per 0.5 s bin) is lower when the target
 * is not the one speaking: demo clip, GT target speech 0.53-0.72 (median 0.67) vs 0.36-0.45 in other-speaker regions. The extractor is NOT able to remove the
 * other speaker (B-only bins sit at 0.36-0.45, not near 0), so this uses health as non-target EVIDENCE only: a TARGET_ONLY bin inside a run of at least
 * [MIN_RUN] consecutive SPEECH bins (VAD speech fraction >= FinalAudioGuardMath.MIN_SPEECH over the bin AND source rms >= the clip-relative gate [REL_SRC_DB] below its median speech level, never under [ABS_FLOOR_DB]) with health below [HEALTH_MAX] is relabelled OTHER_ONLY (and ducked by the render).
 *
 * Gate blindness: relabelled bins leave the retention and target-region gates, so a wrong relabel (ducking real target) would be invisible to them.
 * Hence the abstain: if more than [MAX_RELABEL_SHARE] of the TARGET_ONLY bins would be relabelled, the extractor evidence contradicts the plan and
 * the caller falls back instead of rendering.
 */
object TargetEvidence {
    const val HEALTH_MAX = 0.50f   // PROVISIONAL: eval: 0.50 beats 0.45 (0 false-flags both; 92% vs 58% detection on the demo stretch); thin margin is carried by run>=3, the 40% abstain and the flip gate
    const val MIN_RUN = 3
    /** Level gate is RELATIVE to the clip's own speech level (median rms of VAD-speech bins), not absolute: bench speech sits at -29..-33 dB, so a fixed -26 dB disabled the rule. PROVISIONAL. */
    const val REL_SRC_DB = -10f
    /** Absolute floor so near-silence in a very quiet clip is never evidence. */
    const val ABS_FLOOR_DB = -60f
    const val MAX_RELABEL_SHARE = 0.40f

    sealed class Result {
        /** Labels as given (feature off, or nothing flagged). */
        class Unchanged(val labels: Array<Seg>) : Result()
        class Relabeled(val labels: Array<Seg>, val flaggedBins: Int, val targetOnlyBins: Int) : Result()
        class Contradicted(val flaggedBins: Int, val targetOnlyBins: Int) : Result()
    }

    fun relabel(
        labels: Array<Seg>, source: FloatArray, extracted: FloatArray, speech: BooleanArray, sampleRate: Int = 16000, enabled: Boolean = false
    ): Result {
        require(source.size == extracted.size) { "source/extracted length mismatch" }
        val binLen = (FootageAnalysis.BIN_SEC * sampleRate).toInt()
        require(binLen > 0 && labels.isNotEmpty() && labels.size * binLen >= source.size) { "labels do not cover the audio" }
        require(speech.size * 512L >= source.size) { "speech flags do not cover the audio" }
        if (!enabled) return Result.Unchanged(labels)
        val nb = labels.size
        val srcRms = DoubleArray(nb) { -1.0 }; val extRms = DoubleArray(nb)
        val isSpeech = BooleanArray(nb)
        for (b in 0 until nb) {
            val a = b * binLen; val e = min(source.size, a + binLen)
            if (e - a < binLen / 2) continue
            if (FinalAudioGuardMath.speechFraction(speech, a, e) < FinalAudioGuardMath.MIN_SPEECH) continue   // loud non-speech never counts
            var ss = 0.0; var ee = 0.0
            for (i in a until e) { ss += source[i].toDouble() * source[i]; ee += extracted[i].toDouble() * extracted[i] }
            srcRms[b] = sqrt(ss / (e - a)); extRms[b] = sqrt(ee / (e - a)); isSpeech[b] = true
        }
        val speechLevels = (0 until nb).filter { isSpeech[it] }.map { srcRms[it] }.sorted()
        if (speechLevels.isEmpty()) return Result.Unchanged(labels)
        val median = speechLevels[speechLevels.size / 2]
        val gate = max(median * 10.0.pow(REL_SRC_DB / 20.0), 10.0.pow(ABS_FLOOR_DB / 20.0))
        val evidence = BooleanArray(nb)
        for (b in 0 until nb) {
            if (!isSpeech[b] || labels[b] != Seg.TARGET_ONLY) continue
            evidence[b] = srcRms[b] >= gate && extRms[b] / max(srcRms[b], 1e-12) < HEALTH_MAX
        }
        val out = labels.copyOf()
        var flagged = 0; var i = 0
        while (i < labels.size) {
            if (!evidence[i]) { i++; continue }
            var j = i
            while (j + 1 < labels.size && evidence[j + 1]) j++
            if (j - i + 1 >= MIN_RUN) for (k in i..j) { out[k] = Seg.OTHER_ONLY; flagged++ }
            i = j + 1
        }
        val targetBins = labels.count { it == Seg.TARGET_ONLY }
        if (flagged == 0) return Result.Unchanged(labels)
        if (flagged.toFloat() / targetBins > MAX_RELABEL_SHARE) return Result.Contradicted(flagged, targetBins)
        return Result.Relabeled(out, flagged, targetBins)
    }
}
