package com.akashrajeev.voicebeam.separation

import android.content.Context
import com.akashrajeev.voicebeam.core.ExtractionSafety
import com.akashrajeev.voicebeam.core.VoiceMatch
import com.akashrajeev.voicebeam.ml.VoicePrint

/** Checks waveform reference and speaker retention; scores are heuristics, not guarantees. */
object OfflineQualityGuard {
    fun reference(context: Context, ref: FloatArray): Boolean {
        if(ref.size<32000)return false
        val mask=OfflineSpeechMask.compute(context,ref)
        val embed=VoicePrint(context.assets)
        try {
            val half=ref.size/2
            val a=embed.embed(ref.copyOfRange(0,half))?:return false
            val b=embed.embed(ref.copyOfRange(half,ref.size))?:return false
            val coverage=mask.count{it}.toFloat()/mask.size
            val cosine=VoiceMatch.cosine(a,b)
            android.util.Log.i("OfflineGuard", "reference coverage=$coverage splitCosine=$cosine")
            return ExtractionSafety.referenceTrusted(coverage,cosine)
        } finally {embed.release()}
    }
    fun output(context: Context, source: FloatArray, extracted: FloatArray, ref: FloatArray, speech: BooleanArray): Boolean {
        val embed=VoicePrint(context.assets)
        try {
            val r=embed.embed(ref)?:return false
            var sourceScore=0f;var outputScore=0f;var count=0
            for(a in source.indices step 48000) {
                val b=minOf(a+48000,source.size)
                if(b-a<16000)continue
                val first=a/512;val last=minOf(speech.size,(b+511)/512)
                if((first until last).count{speech[it]}.toFloat()/(last-first)<.5f)continue
                val s=embed.embed(source.copyOfRange(a,b))?:return false
                val y=embed.embed(extracted.copyOfRange(a,b))?:return false
                sourceScore+=VoiceMatch.cosine(s,r);outputScore+=VoiceMatch.cosine(y,r);count++
            }
            if(count==0)return false
            sourceScore/=count;outputScore/=count
            // Mixed-speaker inputs need not resemble the reference; homogeneous inputs must not lose it.
            android.util.Log.i("OfflineGuard", "sourceCosine=$sourceScore outputCosine=$outputScore chunks=$count")
            // Recalibrated (was .4/.6/.15) on a 10-case laptop TitaNet-small distribution (fail max .293, honest min .352); data-thin, revisit with device renders.
            // KNOWN LIMITATION: very noisy references cannot separate a good fallback (.277) from a bad render (.269) -> original shipped (safe). Future: denoise reference before guard embedding.
            return outputScore>=.32f && !(sourceScore>=.6f && outputScore<sourceScore-.45f)
        } finally {embed.release()}
    }
}
