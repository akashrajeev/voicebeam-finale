package com.akashrajeev.voicebeam.core

import kotlin.math.*

/** Conservative heuristics, not a calibrated extraction-confidence model. */
object ExtractionSafety {
    fun referenceTrusted(coverage: Float, splitCosine: Float): Boolean = coverage.isFinite() && splitCosine.isFinite() && coverage >= .5f && splitCosine >= .45f
    /** No amplification in any frame; source speech mask closes pauses with a short fade. */
    fun protect(source: FloatArray, extracted: FloatArray, speech: BooleanArray): FloatArray {
        require(source.size == extracted.size && speech.size == (source.size+511)/512)
        val gains=FloatArray(speech.size)
        for(f in gains.indices) {
            val a=f*512;val b=minOf(a+512,source.size)
            var sr=0.0;var yr=0.0
            for(i in a until b) { sr+=source[i].toDouble()*source[i];yr+=extracted[i].toDouble()*extracted[i] }
            val cap=minOf(1.0,sqrt(sr/(yr+1e-12))).toFloat()
            val voiced=(maxOf(0,f-3)..minOf(speech.lastIndex,f+2)).any { speech[it] }
            gains[f]=if(voiced)cap else 0f
        }
        return FloatArray(source.size) { i ->
            val f=i/512; val prev=if(f==0)gains[0] else gains[f-1]
            val blend=(i%512)/511f
            // Ramp downward immediately; upward fade cannot exceed the current cap.
            val g=minOf(gains[f],prev+(gains[f]-prev)*blend)
            extracted[i]*g
        }
    }
}
