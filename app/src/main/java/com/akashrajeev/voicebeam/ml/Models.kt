package com.akashrajeev.voicebeam.ml

import android.content.res.AssetManager
import android.util.Log
import com.akashrajeev.voicebeam.core.Captions
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

const val SAMPLE_RATE = 16000
private const val TAG = "VoiceBeamModels"

/** Streaming speech-to-text (sherpa-onnx zipformer transducer, runs fully on device). */
class Asr(assets: AssetManager) {
    private val recognizer = com.k2fsa.sherpa.onnx.OfflineRecognizer(
        assetManager = assets,
        config = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
            modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                moonshine = com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig(
                    preprocessor = "models/asr/preprocess.onnx",
                    encoder = "models/asr/encode.int8.onnx",
                    uncachedDecoder = "models/asr/uncached_decode.int8.onnx",
                    cachedDecoder = "models/asr/cached_decode.int8.onnx",
                ), tokens = "models/asr/tokens.txt", numThreads = 1,
            ),
        ),
    )
    private val vad = NeuralVad(assets)
    private val utterance = com.akashrajeev.voicebeam.core.UtteranceBuffer(SAMPLE_RATE)
    fun accept(samples: FloatArray): Pair<String, Boolean> {
        val update = utterance.accept(samples, vad.isVoice(samples)) ?: return Pair(lastText, false)
        val stream = recognizer.createStream()
        val text = try {
            stream.acceptWaveform(update.samples, SAMPLE_RATE)
            recognizer.decode(stream)
            Captions.tidy(recognizer.getResult(stream).text)
        } finally { stream.release() }
        lastText = if (update.ended) "" else text
        return Pair(text, update.ended)
    }
    private var lastText = ""
    fun resetStream() { utterance.reset(); vad.reset(); lastText = "" }
    fun release() { recognizer.release(); vad.release() }
}

/** Probability on fixed 32 ms windows; no segment-onset delay in the monitor path. */
class NeuralVad(assets: AssetManager) {
    private val impl = com.k2fsa.sherpa.onnx.Vad(
        assetManager = assets,
        config = com.k2fsa.sherpa.onnx.VadModelConfig(
            sileroVadModelConfig = com.k2fsa.sherpa.onnx.SileroVadModelConfig(
                model = "models/silero_vad_v5.onnx", windowSize = 512,
            ), sampleRate = SAMPLE_RATE, numThreads = 1,
        ),
    )
    private val window = FloatArray(512)
    private var fill = 0
    var probability = 0f
        private set
    fun isVoice(samples: FloatArray): Boolean {
        var offset = 0
        while (offset < samples.size) {
            val n = minOf(512 - fill, samples.size - offset)
            System.arraycopy(samples, offset, window, fill, n)
            fill += n; offset += n
            if (fill == 512) { probability = impl.compute(window); fill = 0 }
        }
        return probability >= 0.5f
    }
    fun reset() { impl.reset(); fill = 0; probability = 0f }
    fun release() = impl.release()
}

/** Real-time noise removal (GTCRN). */
class Denoiser(assets: AssetManager) {
    private val impl = OnlineSpeechDenoiser(
        assetManager = assets,
        config = OnlineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = "models/gtcrn.onnx"),
                numThreads = 1,
            )
        ),
    )

    val frameShift: Int get() = impl.frameShiftInSamples

    fun process(samples: FloatArray): FloatArray = impl.run(samples, SAMPLE_RATE).samples

    fun reset() = impl.reset()
    fun release() = impl.release()
}

/** Speaker extractor. Packaged profile defaults to NeMo TitaNet Small. */
class VoicePrint(assets: AssetManager, val profile: com.akashrajeev.voicebeam.core.SpeakerProfile = com.akashrajeev.voicebeam.core.SpeakerProfile.DEFAULT) {
    private val extractor = SpeakerEmbeddingExtractor(
        assetManager = assets,
        config = SpeakerEmbeddingExtractorConfig(model = profile.asset, numThreads = 1),
    )

    fun embed(samples: FloatArray): FloatArray? {
        val s = extractor.createStream()
        return try {
            s.acceptWaveform(samples, SAMPLE_RATE)
            s.inputFinished()
            if (extractor.isReady(s)) extractor.compute(s) else null
        } catch (t: Throwable) {
            Log.w(TAG, "embed failed", t); null
        } finally {
            s.release()
        }
    }

    fun release() = extractor.release()
}

/** Loads all audio models once; safe to call from a background thread. */
class AudioModels private constructor(val asr: Asr, val denoiser: Denoiser, val voicePrint: VoicePrint) {
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Releases every native model exactly once; later calls do nothing. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        for (r in listOf<() -> Unit>({ asr.release() }, { denoiser.release() }, { voicePrint.release() })) {
            try { r() } catch (t: Throwable) { Log.w(TAG, "release failed", t) }
        }
    }

    companion object {
        fun load(assets: AssetManager): AudioModels {
            val t0 = System.currentTimeMillis()
            val m = AudioModels(Asr(assets), Denoiser(assets), VoicePrint(assets))
            Log.i(TAG, "models loaded in ${System.currentTimeMillis() - t0} ms")
            return m
        }
    }
}


/** Caption-only streaming DPDFNet-2, owned by caption worker, never audio thread. */
class CaptionDenoiser(assets: AssetManager) {
    private val impl = OnlineSpeechDenoiser(assetManager = assets,
        config = OnlineSpeechDenoiserConfig(model = OfflineSpeechDenoiserModelConfig(
            dpdfnet = com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig(
                model = "models/dpdfnet2.onnx"), numThreads = 1)))
    fun process(samples: FloatArray): FloatArray = impl.run(samples, SAMPLE_RATE).samples
    fun reset() = impl.reset()
    fun release() = impl.release()
}
