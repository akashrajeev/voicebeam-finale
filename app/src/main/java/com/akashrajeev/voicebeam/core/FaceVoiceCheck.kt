package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg

/**
 * Face-only flow helpers. Pure JVM.
 *
 * crossCheck: the tapped face's lips are moving, so the chosen target voice should be present in those bins. If most bins with clear lip
 * activity are labelled OTHER_ONLY (some other voice, target absent), the voice and the face probably belong to different people: abstain
 * instead of isolating the wrong person. Needs enough lip bins to judge; otherwise it does not block.
 *
 * fillGaps: short OTHER_ONLY / NONE islands (at most [MAX_GAP_BINS] bins) sitting between TARGET_ONLY bins, where the tapped lips are moving,
 * are relabelled TARGET_ONLY. A clustering hiccup inside his own sentence should not chop it. PROVISIONAL numbers, one clip.
 */
object FaceVoiceCheck {
    const val MIN_LIP_BINS = 6
    const val MAX_OTHER_SHARE = 0.6f
    const val MAX_GAP_BINS = 2

    sealed class Check {
        class Ok(val note: String) : Check()
        class Mismatch(val note: String) : Check()
    }

    fun crossCheck(labels: Array<Seg>, lipOn: BooleanArray?): Check {
        if (lipOn == null) return Check.Ok("no lip series")
        val n = minOf(labels.size, lipOn.size)
        var lip = 0; var other = 0
        for (i in 0 until n) if (lipOn[i]) { lip++; if (labels[i] == Seg.OTHER_ONLY) other++ }
        if (lip < MIN_LIP_BINS) return Check.Ok("only $lip lip-active bins, not enough to check")
        val share = other.toFloat() / lip
        val note = "$other of $lip lip-active bins carry only another voice (%.0f%%)".format(share * 100)
        return if (share > MAX_OTHER_SHARE) Check.Mismatch(note) else Check.Ok(note)
    }

    class Filled(val labels: Array<Seg>, val bins: Int)

    fun fillGaps(labels: Array<Seg>, lipOn: BooleanArray?): Filled {
        if (lipOn == null) return Filled(labels, 0)
        val out = labels.copyOf(); var filled = 0
        var i = 0
        while (i < labels.size) {
            if (labels[i] == Seg.OTHER_ONLY || labels[i] == Seg.NONE) {
                var j = i
                while (j < labels.size && (labels[j] == Seg.OTHER_ONLY || labels[j] == Seg.NONE)) j++
                val len = j - i
                val before = i > 0 && labels[i - 1] == Seg.TARGET_ONLY
                val after = j < labels.size && labels[j] == Seg.TARGET_ONLY
                val lips = (i until j).all { it < lipOn.size && lipOn[it] }
                if (len <= MAX_GAP_BINS && before && after && lips) { for (k in i until j) out[k] = Seg.TARGET_ONLY; filled += len }
                i = j
            } else i++
        }
        return Filled(out, filled)
    }
}
