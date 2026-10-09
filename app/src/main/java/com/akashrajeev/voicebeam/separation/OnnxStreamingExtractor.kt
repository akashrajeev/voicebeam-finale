package com.akashrajeev.voicebeam.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.akashrajeev.voicebeam.core.Resampler163248
import java.nio.FloatBuffer

/**
 * Streaming target-speech extraction via tse-conv-tasnet-48k ONNX model.
 *
 * Operates at 48 kHz internally, using [Resampler163248] to convert between
 * the 16 kHz pipeline and the 48 kHz model. Each call to [process] receives
 * one 256-sample 16 kHz frame and returns one 256-sample 16 kHz frame.
 *
 * Model I/O (discovered at runtime):
 *   inputs:  audio_chunk [1, 480], cond [1, 192], state_in_0..state_in_88
 *   outputs: extracted_chunk [1, 480], state_out_0..state_out_88
 *
 * Passthrough when no speaker embedding is available.
 */
class OnnxStreamingExtractor(
    private val ortEnv: OrtEnvironment,
    private val ortSession: OrtSession,
) : TargetExtractor {
    override val name = "tse-conv-tasnet-48k"
    override val latencyMs = 20  // ~10 ms model + ~2 ms resampling + headroom

    private val resampler = Resampler163248()
    private var lastEmbedding: FloatArray? = null

    // Pre-allocated buffers (reused every chunk)
    private val chunkBuf = FloatBuffer.allocate(480)
    // TitaNet produces 256-dim embeddings; the ONNX model (ECAPA) expects 192-dim cond.
    // Truncate to first 192 dims — cross-domain + cross-dimensionality, experimental.
    private val speakerDim = 256
    private val modelCondDim = 192
    private val condBuf = FloatBuffer.allocate(modelCondDim)
    private var embeddingTensor: OnnxTensor? = null

    // State tensor management
    private data class StateSlot(
        val inputName: String,
        val outputName: String,
        val shape: LongArray,
    )

    private val stateSlots: List<StateSlot>
    private val stateTensors = LinkedHashMap<String, OnnxTensor>()

    init {
        // Discover state tensors from session metadata
        val inputNames = ortSession.inputNames.filter { it.startsWith("state_in_") }.sorted()
        val outputNames = ortSession.outputNames.filter { it.startsWith("state_out_") }.sorted()
        require(inputNames.isNotEmpty()) { "ONNX model has no state_in_ tensors" }
        require(inputNames.size == outputNames.size) {
            "State tensor count mismatch: ${inputNames.size} inputs, ${outputNames.size} outputs"
        }
        stateSlots = inputNames.mapIndexed { _, inName ->
            val outName = "state_out_" + inName.removePrefix("state_in_")
            val info = ortSession.inputInfo[inName]
            val shape = (info?.info as? TensorInfo)?.shape ?: longArrayOf()
            StateSlot(inName, outName, shape)
        }
        initStates()
    }

    private fun initStates() {
        for (t in stateTensors.values) t.close()
        stateTensors.clear()

        for (slot in stateSlots) {
            val size = slot.shape.fold(1L) { acc, d -> acc * d }.toInt()
            val zeros = FloatArray(size)
            stateTensors[slot.inputName] = OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(zeros), slot.shape)
        }
    }

    override fun process(frame: FloatArray, cue: TargetExtractor.Cue): FloatArray {
        // Cache the latest embedding (TitaNet 256-dim → truncate to model's 192-dim cond)
        val emb = cue.voiceEmbedding
        if (emb != null && emb.size == speakerDim) {
            lastEmbedding = emb
            embeddingTensor?.close()
            condBuf.clear()
            condBuf.put(emb, 0, modelCondDim)
            condBuf.flip()
            embeddingTensor = OnnxTensor.createTensor(ortEnv, condBuf, longArrayOf(1L, modelCondDim.toLong()))
        }

        // Passthrough: no enrollment fingerprint yet
        if (lastEmbedding == null || embeddingTensor == null) return frame

        return try {
            resampler.process(frame, ::runModel)
        } catch (_: Throwable) {
            frame
        }
    }

    /**
     * Called by [Resampler163248] for each 480-sample 48 kHz chunk.
     * Runs the ONNX model and returns the 480-sample 48 kHz output chunk.
     */
    private fun runModel(chunk48k: FloatArray): FloatArray {
        chunkBuf.clear()
        chunkBuf.put(chunk48k)
        chunkBuf.flip()
        val audioTensor = OnnxTensor.createTensor(ortEnv, chunkBuf, longArrayOf(1L, 480L))

        // Build input map (audio + embedding + all state tensors)
        val inputs = LinkedHashMap<String, OnnxTensor>()
        inputs["audio_chunk"] = audioTensor
        inputs["cond"] = embeddingTensor!!
        for ((name, tensor) in stateTensors) {
            inputs[name] = tensor
        }

        val result = ortSession.run(inputs)
        audioTensor.close()

        // Extract output audio
        val optExtracted = result.get("extracted_chunk")
        val extractedTensor = optExtracted.orElse(null) as? OnnxTensor
            ?: run {
                result.close()
                return chunk48k // fallback
            }
        val extracted = FloatArray(480)
        extractedTensor.floatBuffer.get(extracted)
        extractedTensor.close()

        // Thread state tensors: state_out_k becomes next call's state_in_k
        val oldTensors = stateTensors.values.toList()
        stateTensors.clear()
        for (slot in stateSlots) {
            val optOut = result.get(slot.outputName)
            val outTensor = optOut.orElse(null) as? OnnxTensor
            if (outTensor != null) {
                stateTensors[slot.inputName] = outTensor
            } else {
                // Missing output: fallback to zero state
                val size = slot.shape.fold(1L) { acc, d -> acc * d }.toInt()
                stateTensors[slot.inputName] = OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(FloatArray(size)), slot.shape)
            }
        }

        for (t in oldTensors) t.close()
        result.close()

        return extracted
    }

    override fun reset() {
        lastEmbedding = null
        embeddingTensor?.close()
        embeddingTensor = null
        initStates()
        resampler.reset()
    }

    /** Release ONNX resources held by this extractor. */
    fun release() {
        embeddingTensor?.close()
        embeddingTensor = null
        for (t in stateTensors.values) t.close()
        stateTensors.clear()
    }
}