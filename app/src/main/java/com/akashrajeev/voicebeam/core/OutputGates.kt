package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Output safety gates for the routed render.
 *
 * HONESTY NOTE: "retention" below is MIXTURE-RMS retention (output RMS vs source mixture RMS over TARGET_ONLY+OVERLAP bins).
 * It is NOT verified target-only stem retention; in OVERLAP bins the source contains other speakers too. Reports must say so.
 *
 * Band cap is a 3-band cap (0-1k, 1-2k, 2-4k Hz). There is no cap above 4 kHz (the extractor is an 8 kHz-class model).
 * Frames checked: EVERY 512-sample frame including the final partial frame (zero-padded), regardless of VAD flags.
 * The only tolerance is an absolute energy floor (BAND_FLOOR_ENERGY): out_band <= max(src_band * 10^(1/10), BAND_FLOOR_ENERGY),
 * so energy fabricated in a band where the source is empty or very quiet still fails once it exceeds the floor.
 * RMS-based only; correlation between output and source is deliberately NOT used
 * (honest extraction decorrelates from the mix by design). Thresholds come from the eval agent's baseline calibration.
 * These are heuristic checks, not quality certification.
 */
object OutputGates {
    /** Retention is measured over COMBINED TARGET_ONLY + OVERLAP bins. If target-only bins are ever scored separately the floor must be -4 dB. */
    const val RETENTION_FLOOR_DB_COMBINED = -3f
    const val RETENTION_FLOOR_DB_TARGET_ONLY = -4f
    /** On NONE bins: out RMS / mix RMS must be <= 0.05 (-26 dB). Honest protect() measures about 0.013-0.015. */
    const val NONE_BIN_MAX_RATIO = 0.05f
    /** Per 512-sample voiced bin, bands below 4 kHz: out_band <= src_band + 1 dB. No cap above 4 kHz. */
    const val BAND_EXCESS_MAX_DB = 1f
    /** Absolute per-band energy floor (same units as bandEnergy). About 50-60 dB below speech-band energy, above the splatter of a 10 ms fade edge (about 3e-9); musical-noise artifacts sit around 1e-5..1e-4. Needs real-clip recalibration by the eval harness. */
    const val BAND_FLOOR_ENERGY = 1e-8
    const val BIN = 512
    private const val EPS = 1e-9

    class Result(
        val passed: Boolean,
        val retentionDb: Float,
        val noneRatio: Float?,           // null when there are no NONE bins
        val worstBandExcessDb: Float,    // worst over all frames and the 3 capped bands (<= 0 when every band is at or under the source)
        val failures: List<String>
    )

    fun check(
        output: FloatArray, source: FloatArray, labels: Array<Seg>, sampleRate: Int = 16000
    ): Result {
        require(output.size == source.size) { "length mismatch" }
        require(sampleRate == 16000) { "band gate is defined for 16 kHz analysis audio" }
        require(output.all { it.isFinite() } && source.all { it.isFinite() }) { "non-finite samples" }
        val binLen = (FootageAnalysis.BIN_SEC * sampleRate).toInt()
        require(labels.isNotEmpty() && labels.size * binLen >= source.size) { "labels do not cover the audio" }
        val frames = (source.size + BIN - 1) / BIN
        val fails = ArrayList<String>()

        // 1) retention on combined TARGET_ONLY + OVERLAP bins
        var so = 0.0; var ss = 0.0; var n = 0
        // 2) NONE bins
        var no = 0.0; var ns = 0.0; var nn = 0
        for (i in source.indices) {
            when (labels[min(labels.size - 1, i / binLen)]) {
                Seg.TARGET_ONLY, Seg.OVERLAP -> { so += output[i].toDouble() * output[i]; ss += source[i].toDouble() * source[i]; n++ }
                Seg.NONE -> { no += output[i].toDouble() * output[i]; ns += source[i].toDouble() * source[i]; nn++ }
                Seg.OTHER_ONLY -> {}
            }
        }
        val retention = if (n == 0 || ss < EPS) 0f else (10 * log10((so + EPS) / (ss + EPS))).toFloat()
        if (n > 0 && ss >= EPS && retention < RETENTION_FLOOR_DB_COMBINED) fails.add("retention ${"%.1f".format(retention)} dB < $RETENTION_FLOOR_DB_COMBINED dB")
        var noneRatio: Float? = null
        if (nn > 0) {
            val r = sqrt((no + EPS * EPS) / (ns + EPS * EPS)).toFloat()
            noneRatio = r
            if (ns >= EPS && r > NONE_BIN_MAX_RATIO) fails.add("none-bin ratio ${"%.3f".format(r)} > $NONE_BIN_MAX_RATIO")
            else if (ns < EPS && sqrt(no / nn) > 1e-3) fails.add("none-bin output not silent on silent source")
        }

        // 3) 3-band cap on every frame (full and final partial), all VAD states
        var worst = -200f
        val allow = 10.0.pow(BAND_EXCESS_MAX_DB / 10.0)
        for (f in 0 until frames) {
            val a = f * BIN
            val bs = bandEnergy(source, a); val bo = bandEnergy(output, a)
            for (k in 0 until 3) {
                val limit = max(bs[k] * allow, BAND_FLOOR_ENERGY)
                if (bo[k] > limit) worst = max(worst, (10 * log10((bo[k] + 1e-15) / (max(bs[k], BAND_FLOOR_ENERGY)))).toFloat())
                else worst = max(worst, min(0f, (10 * log10((bo[k] + 1e-15) / (max(bs[k], BAND_FLOOR_ENERGY)))).toFloat()))
            }
        }
        if (worst > BAND_EXCESS_MAX_DB) fails.add("band excess ${"%.1f".format(worst)} dB > $BAND_EXCESS_MAX_DB dB")
        return Result(fails.isEmpty(), retention, noneRatio, worst, fails)
    }

    /** Energy per band [0-1k, 1-2k, 2-4k, 4-8k] of one Hann-windowed, zero-padded 512-sample bin at 16 kHz (bin width 31.25 Hz). */
    internal fun bandEnergy(x: FloatArray, start: Int): DoubleArray {
        val re = DoubleArray(BIN); val im = DoubleArray(BIN)
        val len = min(BIN, x.size - start) // final partial frame is zero-padded
        for (i in 0 until len) re[i] = x[start + i] * (0.5 - 0.5 * cos(2 * PI * i / (BIN - 1)))
        fft(re, im)
        val e = DoubleArray(4)
        for (k in 1 until BIN / 2) {
            val p = (re[k] * re[k] + im[k] * im[k]) / (BIN.toDouble() * BIN)
            val hz = k * 16000.0 / BIN
            val b = when { hz < 1000 -> 0; hz < 2000 -> 1; hz < 4000 -> 2; else -> 3 }
            e[b] += p
        }
        return e
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val wr = cos(ang * k); val wi = sin(ang * k)
                val ur = re[i + k]; val ui = im[i + k]
                val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                re[i + k] = ur + vr; im[i + k] = ui + vi
                re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
            }
            len = len shl 1
        }
    }
}
