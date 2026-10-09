package com.akashrajeev.voicebeam.core

import kotlin.math.*

/** Hearing-only Butterworth80Hz high-pass. Not voice extraction or clinical tuning. */
class HearingRumbleFilter(sampleRate: Int = 16000) {
    private val w = 2.0 * PI * 80.0 / sampleRate
    private val c = cos(w)
    private val a = sin(w) / sqrt(2.0)
    private val b0 = (1.0+c) / (2.0*(1.0+a))
    private val b1 = -(1.0+c) / (1.0+a)
    private val b2 = b0
    private val a1 = -2.0*c / (1.0+a)
    private val a2 = (1.0-a) / (1.0+a)
    private var x1=0.0; private var x2=0.0
    private var y1=0.0; private var y2=0.0
    fun reset() { x1=0.0;x2=0.0;y1=0.0;y2=0.0 }
    fun process(samples: FloatArray, n: Int) {
        require(n in 0..samples.size)
        for (k in 0 until n) {
            val x=samples[k].toDouble()
            if (!x.isFinite()) { samples[k]=0f;reset();continue }
            val y=b0*x+b1*x1+b2*x2-a1*y1-a2*y2
            if (!y.isFinite()) { reset();continue }
            x2=x1;x1=x;y2=y1;y1=y
            samples[k]=y.toFloat()
        }
    }
}
