package com.akashrajeev.voicebeam.core

import kotlin.math.*

/** Offline 16k/8k conversion only; never changes live mic/identity audio. */
object ExtractionRate {
    private val taps = DoubleArray(63) { k ->
        val m=k-31; val f=3600.0/16000
        (if(m==0)2*f else sin(2*PI*f*m)/(PI*m))*(.54-.46*cos(2*PI*k/62))
    }.also { a -> val sum=a.sum(); for(k in a.indices)a[k]/=sum }
    fun down(input: FloatArray): FloatArray = FloatArray(input.size/2) { j ->
        var v=0.0
        for(k in taps.indices) { val i=2*j+k-31; if(i in input.indices)v+=input[i]*taps[k] }
        v.toFloat()
    }
    fun up(input: FloatArray, size: Int): FloatArray = FloatArray(size) { i ->
        var v=0.0
        for(k in taps.indices) { val p=i+k-31; if(p%2==0 && p/2 in input.indices)v+=2*input[p/2]*taps[k] }
        v.toFloat()
    }
    fun valid(input: FloatArray): Boolean = input.isNotEmpty() && input.all { it.isFinite() } &&
        sqrt(input.sumOf { it.toDouble()*it }/input.size) >= .0001
}
