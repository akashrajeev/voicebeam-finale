package com.akashrajeev.voicebeam.separation

import android.content.Context
import android.os.SystemClock
import ai.onnxruntime.*
import com.akashrajeev.voicebeam.core.ExtractionRate
import com.akashrajeev.voicebeam.core.WavWriter
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

/** Real, noncausal target extraction AFTER recording. Never used for live listening. */
object OfflineSpeakerBeam {
    private const val HASH="e9bdb6c0a8e51b8341435f49abe59ead136cd2b9d6b6c178990fdc50bf54c9eb"
    /** [isCancelled] is polled before model load, before each 6 s block and after the loop; default false keeps old callers unchanged. A block in flight (~seconds) is not interrupted. */
    @Synchronized fun extract(context: Context, raw: File, reference: FloatArray, out: File, isCancelled: () -> Boolean = { false }): Long {
        if(isCancelled()) throw kotlinx.coroutines.CancellationException("Offline extraction cancelled")
        require(raw.length() <= 16000L*2*120+44) { "Offline extraction supports up to 2 minutes" }
        require(reference.size >= 16000 && ExtractionRate.valid(reference)) { "Learn target voice again first" }
        val (samples,sr)=WavWriter.read(raw)
        require(sr==16000 && samples.size<=16000*120 && ExtractionRate.valid(samples)) { "Invalid recording" }
        val model=context.assets.open("models/speakerbeam-8k.onnx").use { it.readBytes() }
        val digest=MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) }
        require(digest==HASH) { "Extractor model hash mismatch" }
        val start=SystemClock.elapsedRealtime()
        val env=OrtEnvironment.getEnvironment()
        val options=OrtSession.SessionOptions().apply { setIntraOpNumThreads(1);setInterOpNumThreads(1) }
        val mono=ExtractionRate.down(samples); val ref=ExtractionRate.down(reference)
        val output=FloatArray(mono.size)
        options.use { opt -> env.createSession(model,opt).use { session ->
            OnnxTensor.createTensor(env,FloatBuffer.wrap(ref),longArrayOf(1,ref.size.toLong())).use { enroll ->
                // 6s central blocks with .5s context on each side. Offline, not causal.
                var offset=0
                while(offset<mono.size) {
                    if(isCancelled()) throw kotlinx.coroutines.CancellationException("Offline extraction cancelled")
                    require(SystemClock.elapsedRealtime()-start<180000) { "Offline extraction timed out" }
                    val end=minOf(offset+48000,mono.size)
                    val left=maxOf(0,offset-4000);val right=minOf(mono.size,end+4000)
                    val original=right-left
                    val padded=maxOf(32,((original-32+15)/16)*16+32)
                    val block=FloatArray(padded);mono.copyInto(block,0,left,right)
                    OnnxTensor.createTensor(env,FloatBuffer.wrap(block),longArrayOf(1,padded.toLong())).use { mix ->
                        session.run(mapOf("mixture" to mix,"enrollment" to enroll)).use { result ->
                            val tensor=(result.get("extracted").orElse(null) as? OnnxTensor) ?: error("Missing extracted output")
                            val data=FloatArray(padded);tensor.floatBuffer.get(data)
                            require(data.all { it.isFinite() }) { "Non-finite extracted audio" }
                            data.copyInto(output,offset,offset-left,end-left)
                        }
                    }
                    offset=end
                }
            }
        } }
        if(isCancelled()) throw kotlinx.coroutines.CancellationException("Offline extraction cancelled")
        require(ExtractionRate.valid(output)) { "Extractor returned silence" }
        require(SystemClock.elapsedRealtime()-start<180000) { "Offline extraction timed out" }
        val full=ExtractionRate.up(output,samples.size)
        require(full.all { it.isFinite() })
        val peak=full.maxOf { kotlin.math.abs(it) }
        val scale=if(peak>0.98f)0.98f/peak else 1f
        val temp=File(out.parentFile,out.name+".tmp")
        try {
            WavWriter(temp,16000).use { w -> w.write(FloatArray(full.size) { full[it]*scale }) }
            require(temp.renameTo(out)) { "Cannot save extracted audio" }
        } finally { temp.delete() }
        return SystemClock.elapsedRealtime()-start
    }
}
