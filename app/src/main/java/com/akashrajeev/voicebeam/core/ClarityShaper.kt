package com.akashrajeev.voicebeam.core

import kotlin.math.*

/**
 * Speech presence lift for the listen path only. A +3.5 dB peaking filter at 2.8 kHz,
 * crossfaded in only while the gate is fully open on the target (gate gain >= 0.9).
 * When the gate is attenuating others the weight is zero, so background gets no extra
 * level. Applied after DFN and the rumble filter, before the gate gain and boost.
 * Captions, VAD and voice match never see it.
 */
class ClarityShaper(sampleRate: Int = 16000, centerHz: Double = 2800.0, gainDb: Double = 3.5, q: Double = 1.0) {
    private val b0: Double; private val b1: Double; private val b2: Double
    private val a1: Double; private val a2: Double
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    private var weight = 0f
    init {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * centerHz / sampleRate
        val alpha = sin(w) / (2.0 * q)
        val c = cos(w)
        val n0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / n0; b1 = (-2.0 * c) / n0; b2 = (1.0 - alpha * a) / n0
        a1 = (-2.0 * c) / n0; a2 = (1.0 - alpha / a) / n0
    }
    fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0; weight = 0f }
    /** Target weight: 0 below gate gain 0.9 or when boost is not allowed, 1 at full open. */
    fun targetWeight(gateGain: Float, allowed: Boolean): Float =
        if (!allowed || !gateGain.isFinite()) 0f else ((gateGain - .9f) / .1f).coerceIn(0f, 1f)
    fun process(samples: FloatArray, n: Int, gateGain: Float, allowed: Boolean) {
        require(n in 0..samples.size)
        val wantedWeight = targetWeight(gateGain, allowed)
        val start = weight
        for (k in 0 until n) {
            val x = samples[k].toDouble()
            if (!x.isFinite()) { reset(); samples[k] = 0f; continue }
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            if (!y.isFinite()) { reset(); continue }
            x2 = x1; x1 = x; y2 = y1; y1 = y
            val wt = start + (wantedWeight - start) * ((k + 1f) / n)
            samples[k] = (x + wt * (y - x)).toFloat()
        }
        weight = wantedWeight
    }
}
