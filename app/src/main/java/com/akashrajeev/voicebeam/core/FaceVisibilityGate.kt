package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg

/**
 * "Target face not visible -> not the target's voice": relabel TARGET_ONLY (and optionally OVERLAP) bins where the tapped face track has no data as OTHER_ONLY, which the
 * render then mutes. Pure JVM. Only meaningful when the user picked the target by face tap (a typed reference has no face track: pass visible = null, labels unchanged).
 *
 * A blink or one missed detection must not chop the target: a bin counts as visible if the face was seen in it or within [HOLD_BINS] bins either side (0.5 s each).
 * A broken tracker must not mute the whole clip: if more than [MAX_MUTE_SHARE] of the TARGET_ONLY bins would be muted, the face evidence is not trusted
 * and the labels stay audio-only (result says why). PROVISIONAL numbers, one clip.
 * Known costs: target speech while the target is off-screen is muted (by design); a face that leaves and re-enters gets a new tracker id, so its later bins stay muted.
 */
object FaceVisibilityGate {
    const val HOLD_BINS = 1
    const val MAX_MUTE_SHARE = 0.60f

    sealed class Result {
        class Unchanged(val labels: Array<Seg>, val reason: String) : Result()
        class Relabeled(val labels: Array<Seg>, val mutedBins: Int, val targetOnlyBins: Int) : Result()
    }

    fun relabel(labels: Array<Seg>, visible: BooleanArray?, muteOverlap: Boolean = true): Result {
        if (visible == null) return Result.Unchanged(labels, "no face track (typed reference): audio-only labels")
        // the face feed and the decoded audio can differ by a bin at the end: pad that last bin as "seen" (never mute because of a rounding gap), anything more is distrusted
        if (visible.size < labels.size - 1) return Result.Unchanged(labels, "face visibility does not cover the audio: audio-only labels")
        val seen = BooleanArray(labels.size) { b ->
            b >= visible.size || (maxOf(0, b - HOLD_BINS)..minOf(visible.size - 1, b + HOLD_BINS)).any { visible[it] }
        }
        val t = labels.count { it == Seg.TARGET_ONLY }
        val muteT = labels.indices.count { labels[it] == Seg.TARGET_ONLY && !seen[it] }
        if (t > 0 && muteT.toFloat() / t > MAX_MUTE_SHARE)
            return Result.Unchanged(labels, "face track too sparse ($muteT of $t target bins unseen): audio-only labels")
        val keptT = labels.indices.count { labels[it] == Seg.TARGET_ONLY && seen[it] }
        if (t > 0 && keptT == 0) return Result.Unchanged(labels, "target face never seen in a target bin: audio-only labels (would mute everything)")
        var muted = 0
        val out = Array(labels.size) { b ->
            val l = labels[b]
            if (!seen[b] && (l == Seg.TARGET_ONLY || (muteOverlap && l == Seg.OVERLAP))) { muted++; Seg.OTHER_ONLY } else l
        }
        return if (muted == 0) Result.Unchanged(labels, "target face visible throughout") else Result.Relabeled(out, muted, t)
    }
}
