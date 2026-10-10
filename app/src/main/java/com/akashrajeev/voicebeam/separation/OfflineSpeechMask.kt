package com.akashrajeev.voicebeam.separation

import android.content.Context
import ai.onnxruntime.*
import java.nio.FloatBuffer

/** SileroV5 512-sample windows WITH64-sample context; independent of live VAD. */
object OfflineSpeechMask {
    fun compute(context: Context, samples: FloatArray): BooleanArray {
        val env=OrtEnvironment.getEnvironment()
        val model=context.assets.open("models/silero_vad_v5.onnx").use{it.readBytes()}
        val mask=BooleanArray((samples.size+511)/512)
        var state=Array(2){Array(1){FloatArray(128)}}
        var history=FloatArray(64)
        OrtSession.SessionOptions().apply{setIntraOpNumThreads(1)}.use { options -> env.createSession(model,options).use { session ->
            OnnxTensor.createTensor(env,16000L).use { rate ->
                for(f in mask.indices) {
                    val frame=FloatArray(576);history.copyInto(frame)
                    samples.copyInto(frame,64,f*512,minOf((f+1)*512,samples.size))
                    OnnxTensor.createTensor(env,FloatBuffer.wrap(frame),longArrayOf(1,576)).use { input ->
                        OnnxTensor.createTensor(env,state).use { st ->
                            session.run(mapOf("input" to input,"state" to st,"sr" to rate)).use { output ->
                                @Suppress("UNCHECKED_CAST") val p=(output[0].value as Array<FloatArray>)[0][0]
                                mask[f]=p>=.5f
                                @Suppress("UNCHECKED_CAST") val next=output[1].value as Array<Array<FloatArray>>
                                state=next
                            }
                        }
                    }
                    history=frame.copyOfRange(512,576)
                }
            }
        } }
        return mask
    }
}
