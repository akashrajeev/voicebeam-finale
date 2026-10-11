package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg

/**
 * "Target lips not moving -> target is not talking": relabel TARGET_ONLY/OVERLAP bins where the tapped face's lips were not active within [HOLD_BINS] bins either side
 * as OTHER_ONLY, which the render mutes (true silence). Pure JVM. Only applies to a face-tap target (lipOn == null -> labels unchanged).
 * The hold keeps word gaps, trailing syllables and a missed lip frame from chopping the target. A weak lip signal must never mute the target: if more than
 * [MAX_MUTE_SHARE] of the target bins would be muted, or nothing would be left, the lip evidence is not trusted and labels stay as they were.
 * PROVISIONAL numbers, one clip. Known cost: target speech while the mouth is occluded/turned away is muted.
 */
object LipSyncGate {
    const val HOLD_BINS = 2
    const val MAX_MUTE_SHARE = 0.5f

    sealed class Result {
        class Unchanged(val labels: Array<Seg>, val reason: String) : Result()
        class Relabeled(val labels: Array<Seg>, val mutedBins: Int, val keptBins: Int) : Result()
    }

    fun relabel(labels: Array<Seg>, lipOn: BooleanArray?): Result {
        if (lipOn == null) return Result.Unchanged(labels, "no lip series")
        if (lipOn.size < labels.size - 1) return Result.Unchanged(labels, "lip series does not cover the audio")
        val talking = BooleanArray(labels.size) { b ->
            b >= lipOn.size || (maxOf(0, b - HOLD_BINS)..minOf(lipOn.size - 1, b + HOLD_BINS)).any { lipOn[it] }
        }
        val isT = { l: Seg -> l == Seg.TARGET_ONLY || l == Seg.OVERLAP }
        val total = labels.count(isT)
        if (total == 0) return Result.Unchanged(labels, "no target bins")
        val mute = labels.indices.count { isT(labels[it]) && !talking[it] }
        val kept = total - mute
        if (mute == 0) return Result.Unchanged(labels, "target lips active in every target bin")
        if (kept == 0) return Result.Unchanged(labels, "lip series would mute every target bin: not trusted")
        if (mute.toFloat() / total > MAX_MUTE_SHARE) return Result.Unchanged(labels, "lip series would mute $mute of $total target bins: not trusted")
        return Result.Relabeled(Array(labels.size) { if (isT(labels[it]) && !talking[it]) Seg.OTHER_ONLY else labels[it] }, mute, kept)
    }
}
