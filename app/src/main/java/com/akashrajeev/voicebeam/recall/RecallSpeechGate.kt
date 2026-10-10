package com.akashrajeev.voicebeam.recall

import android.content.Context
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig

/** Auxiliary speech-presence detector only. Gemma remains the sole ASR engine. */
class RecallSpeechGate(context: Context) : AutoCloseable {
    private val vad=Vad(context.assets,VadModelConfig(
        sileroVadModelConfig=SileroVadModelConfig(model="models/silero_vad_v5.onnx",windowSize=512),
        sampleRate=16000,numThreads=1))
    fun speechOnly(samples: FloatArray, threshold: Float=0.7f): FloatArray? {
        vad.reset()
        val mask=BooleanArray((samples.size+511)/512);val window=FloatArray(512)
        for(i in mask.indices) {
            val start=i*512;window.fill(0f);samples.copyInto(window,0,start,minOf(start+512,samples.size))
            mask[i]=vad.compute(window)>=threshold
        }
        // At least 1 second of detected speech, plus a consecutive run of ~192 ms.
        var streak=0;var longest=0
        mask.forEach { if(it) streak++ else streak=0;longest=maxOf(longest,streak) }
        if(mask.count { it }*512<16000 || longest<6) return null
        // Add 160 ms boundary padding. Original WAV is retained unchanged for replay.
        val keep=BooleanArray(mask.size)
        mask.forEachIndexed { i,speech -> if(speech) for(k in maxOf(0,i-5)..minOf(mask.lastIndex,i+5)) keep[k]=true }
        val count=keep.indices.sumOf { if(keep[it]) minOf(512,samples.size-it*512) else 0 }
        val out=FloatArray(count);var pos=0
        keep.forEachIndexed { i,k -> if(k) { val n=minOf(512,samples.size-i*512);samples.copyInto(out,pos,i*512,i*512+n);pos+=n } }
        return out
    }
    override fun close() { vad.release() }
}
