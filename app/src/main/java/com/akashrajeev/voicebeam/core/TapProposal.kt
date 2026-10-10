package com.akashrajeev.voicebeam.core

import kotlin.math.max
import kotlin.math.min

/**
 * Face-tap picker, pure part: given the tapped face's per-0.5 s lip activity, the other tracked faces' lip silence and the VAD mask, PROPOSE the time interval for the
 * "only my target speaks here" reference. The interval is a request, not a label: the planner still validates it and every output gate still applies.
 * Evidence ranks: STRONG = target lips on AND all other tracked faces have data and are silent; WEAK = no other face is tracked (a speaker off-screen is possible),
 * so the user must confirm the interval. Never proposes under [MIN_SEC] or over [MAX_SEC].
 */
object TapProposal {
    const val MIN_SEC = 3f
    const val MAX_SEC = 10f
    /** A bin is usable only if at least this fraction of its VAD frames is speech. */
    const val MIN_BIN_SPEECH = 0.5f
    enum class Strength { STRONG, WEAK }
    class Proposal(val interval: FootageAnalysis.Interval, val strength: Strength, val speechShare: Float)

    fun propose(targetLipOn: BooleanArray, othersOff: BooleanArray?, speech: BooleanArray, durationSec: Float, sampleRate: Int = 16000): Proposal? {
        require(durationSec.isFinite() && durationSec > 0f) { "bad duration" }
        val bins = Math.ceil((durationSec / FootageAnalysis.BIN_SEC).toDouble()).toInt()
        require(targetLipOn.size == bins) { "lip series must have one flag per 0.5 s bin" }
        require(othersOff == null || othersOff.size == bins) { "others series must have one flag per 0.5 s bin" }
        val binLen = (FootageAnalysis.BIN_SEC * sampleRate).toInt()
        require(speech.size * 512L >= (durationSec * sampleRate).toLong() - binLen) { "speech flags do not cover the audio" }
        val share = FloatArray(bins) { b ->
            val a = b * binLen; val e = min((durationSec * sampleRate).toInt(), a + binLen)
            if (e - a < binLen / 2 || (e + 511) / 512 > speech.size) 0f else FootageWindows.speechCoverage(speech, a, e)
        }
        val ok = BooleanArray(bins) { targetLipOn[it] && share[it] >= MIN_BIN_SPEECH && (othersOff?.get(it) ?: true) }
        val minBins = Math.ceil((MIN_SEC / FootageAnalysis.BIN_SEC).toDouble()).toInt()
        val maxBins = (MAX_SEC / FootageAnalysis.BIN_SEC).toInt()
        var best: IntArray? = null; var bestScore = -1f
        var i = 0
        while (i < bins) {
            if (!ok[i]) { i++; continue }
            var j = i; while (j + 1 < bins && ok[j + 1]) j++
            val len = j - i + 1
            if (len >= minBins) {
                // best maxBins-wide (or whole-run) window by mean speech share
                val w = min(len, maxBins)
                for (s in i..(j - w + 1)) {
                    val sc = (s until s + w).sumOf { share[it].toDouble() }.toFloat() / w + 0.001f * w   // tie-break toward longer
                    if (sc > bestScore) { bestScore = sc; best = intArrayOf(s, s + w) }
                }
            }
            i = j + 1
        }
        val b = best ?: return null
        var start = b[0] * FootageAnalysis.BIN_SEC; var end = b[1] * FootageAnalysis.BIN_SEC
        if (end - start >= MIN_SEC + 2 * 0.25f) { start += 0.25f; end -= 0.25f }          // inset: bin edges are where the speaker may still be changing
        end = min(end, durationSec); start = max(0f, start)
        if (end - start < MIN_SEC) return null
        val sp = (b[0] until b[1]).sumOf { share[it].toDouble() }.toFloat() / (b[1] - b[0])
        return Proposal(FootageAnalysis.Interval(start, end), if (othersOff != null) Strength.STRONG else Strength.WEAK, sp)
    }
}
