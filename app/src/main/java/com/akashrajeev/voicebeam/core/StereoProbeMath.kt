package com.akashrajeev.voicebeam.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure analysis for the stereo-capture probe (candidate C3, direction cue). Answers one question: do the two captured channels carry DIFFERENT information
 * (level and/or arrival-time differences between speakers), or are they duplicates / one dead channel? No thresholds here are tuned; they only classify the probe.
 */
object StereoProbeMath {
    enum class Verdict { TOO_QUIET, ONE_CHANNEL_DEAD, DUPLICATE_CHANNELS, DISTINCT_CHANNELS }

    class Result(
        val rmsLeftDb: Float, val rmsRightDb: Float,
        /** 20 log10(rmsL / rmsR): inter-channel level difference. */
        val ildDb: Float,
        /** Normalised correlation at the best lag, in [-1, 1]. */
        val peakCorrelation: Float,
        /** Lag (samples, right relative to left) of the correlation peak within +-[MAX_LAG]; sign says which mic hears the source first. */
        val bestLagSamples: Int,
        val verdict: Verdict
    )

    const val MIN_RMS_DB = -60f
    const val DEAD_DB_BELOW_OTHER = 40f
    const val DUPLICATE_CORR = 0.995f
    const val DUPLICATE_ILD_DB = 0.5f
    /** A phone's mics are a few cm to 15 cm apart: at 48 kHz that is at most about 21 samples; allow 32. */
    const val MAX_LAG = 32

    fun analyze(left: FloatArray, right: FloatArray): Result {
        require(left.size == right.size && left.size >= 1024) { "need equal-length channels of at least 1024 samples" }
        require(left.all { it.isFinite() } && right.all { it.isFinite() }) { "non-finite samples" }
        val n = left.size
        var sl = 0.0; var sr = 0.0
        for (i in 0 until n) { sl += left[i].toDouble() * left[i]; sr += right[i].toDouble() * right[i] }
        val rl = sqrt(sl / n); val rr = sqrt(sr / n)
        val dbL = (20 * log10(rl + 1e-12)).toFloat(); val dbR = (20 * log10(rr + 1e-12)).toFloat()
        var bestC = -2f; var bestLag = 0
        if (sl > 1e-12 && sr > 1e-12) {
            val denom = sqrt(sl * sr)
            for (lag in -MAX_LAG..MAX_LAG) {
                var acc = 0.0
                val a = max(0, -lag); val b = min(n, n - lag)
                for (i in a until b) acc += left[i].toDouble() * right[i + lag]
                val c = (acc / denom).toFloat()
                if (c > bestC) { bestC = c; bestLag = lag }
            }
        } else bestC = 0f
        val ild = dbL - dbR
        val verdict = when {
            max(dbL, dbR) < MIN_RMS_DB -> Verdict.TOO_QUIET
            abs(dbL - dbR) > DEAD_DB_BELOW_OTHER -> Verdict.ONE_CHANNEL_DEAD
            bestC >= DUPLICATE_CORR && abs(ild) < DUPLICATE_ILD_DB && bestLag == 0 -> Verdict.DUPLICATE_CHANNELS
            else -> Verdict.DISTINCT_CHANNELS
        }
        return Result(dbL, dbR, ild, bestC, bestLag, verdict)
    }

    /** Per-speaker direction evidence: the same probe run on two speaker-solo stretches; the speakers are separable by direction when their ILD or lag differ. */
    fun speakersDirectionallySeparable(a: Result, b: Result, minIldDiffDb: Float = 2f, minLagDiff: Int = 1): Boolean =
        a.verdict == Verdict.DISTINCT_CHANNELS && b.verdict == Verdict.DISTINCT_CHANNELS &&
            (abs(a.ildDb - b.ildDb) >= minIldDiffDb || abs(a.bestLagSamples - b.bestLagSamples) >= minLagDiff)
}
