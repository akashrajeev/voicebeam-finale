package com.akashrajeev.voicebeam.core

import kotlin.math.*

/** Fixed3xrate converter and480sample framing. No future input; mono16k->48k->16k. */
class DfnFrameAdapter(private val run: (FloatArray) -> FloatArray) {
    // Windowed sinc anti-image/anti-alias FIR.95tapsat48k,cutoff7.6kHz.
    private val taps=DoubleArray(95) { k ->
        val m=k-47; val f=7600.0/48000
        (if(m==0)2*f else sin(2*PI*f*m)/(PI*m))*(.54-.46*cos(2*PI*k/94))
    }.also { a -> val sum=a.sum();for(k in a.indices)a[k]/=sum }
    private val up=Fir(taps);private val down=Fir(taps)
    private val chunk=FloatArray(480);private var fill=0;private var phase=0
    private val output=ArrayDeque<Float>().also { repeat(256){_->it.addLast(0f)} }
    fun process(input: FloatArray): FloatArray {
        require(input.size==256&&input.all{it.isFinite()})
        for(v in input) for(p in 0..2) {
            chunk[fill++]=up.sample(if(p==0)v*3f else 0f)
            if(fill==480) {
                val result=run(chunk.copyOf());require(result.size==480&&result.all{it.isFinite()})
                for(x in result) { val y=down.sample(x);if(phase++%3==0)output.addLast(y) }
                fill=0
            }
        }
        require(output.size>=256&&output.size<=576)
        return FloatArray(256){output.removeFirst()}
    }
    private class Fir(private val taps: DoubleArray) {
        private val delay=DoubleArray(taps.size);private var cursor=0
        fun sample(x:Float):Float {
            delay[cursor]=x.toDouble();var y=0.0
            for(k in taps.indices)y+=taps[k]*delay[(cursor-k+delay.size)%delay.size]
            cursor=(cursor+1)%delay.size
            return y.toFloat()
        }
    }
}
