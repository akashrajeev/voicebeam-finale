package com.akashrajeev.voicebeam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.core.VoiceMatch
import com.akashrajeev.voicebeam.core.WavWriter
import com.akashrajeev.voicebeam.ml.AudioModels
import com.akashrajeev.voicebeam.record.MediaExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/** Runs the real on-device models on real audio. */
@RunWith(AndroidJUnit4::class)
class ModelsTest {
    companion object {
        lateinit var models: AudioModels
        lateinit var speech: FloatArray

        @BeforeClass @JvmStatic fun load() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            models = AudioModels.load(ctx.assets)
            val testCtx = InstrumentationRegistry.getInstrumentation().context
            val f = File(ctx.cacheDir, "speech.wav")
            testCtx.assets.open("test_speech.wav").use { i -> f.outputStream().use { i.copyTo(it) } }
            val (s, sr) = WavWriter.read(f)
            assertEquals(16000, sr)
            speech = s
        }
    }

    private fun transcribe(samples: FloatArray): String {
        val asr = models.asr
        asr.resetStream()
        val out = StringBuilder()
        var last = ""
        val padded = samples + FloatArray(16000)
        var i = 0
        while (i < padded.size) {
            val n = minOf(1600, padded.size - i)
            val (text, ended) = asr.accept(padded.copyOfRange(i, i + n))
            if (ended) { if (text.isNotBlank()) out.append(text).append(' '); last = "" } else last = text
            i += n
        }
        out.append(last)
        return out.toString().lowercase()
    }

    @Test fun streamingAsrUnderstandsSpeech() {
        val text = transcribe(speech)
        android.util.Log.i("VoiceBeamTest", "ASR: $text")
        assertTrue("got: $text", listOf("yellow", "lamps", "night", "quarter").count { text.contains(it) } >= 2)
    }

    @Test fun denoiserRemovesNoiseAndKeepsWords() {
        val rnd = Random(7)
        val noisy = FloatArray(speech.size) { speech[it] + (rnd.nextFloat() - 0.5f) * 0.12f }
        val d = models.denoiser
        d.reset()
        val shift = d.frameShift
        val out = ArrayList<Float>(noisy.size)
        var i = 0
        while (i + shift <= noisy.size) { d.process(noisy.copyOfRange(i, i + shift)).forEach { out.add(it) }; i += shift }
        val clean = out.toFloatArray()
        assertTrue(clean.size > noisy.size / 2)
        // Noise-only stretch at the start of padded audio: energy should drop.
        val quietIn = rms(FloatArray(16000) { (rnd.nextFloat() - 0.5f) * 0.12f }.let { n -> d.reset(); n.toList().chunked(shift).filter { it.size == shift }.flatMap { d.process(it.toFloatArray()).toList() }.toFloatArray() })
        val noiseLevel = 0.12f / kotlin.math.sqrt(12f)
        assertTrue("noise $quietIn vs $noiseLevel", quietIn < noiseLevel * 0.5f)
        val text = transcribe(clean)
        assertTrue("after denoise got: $text", listOf("yellow", "lamps", "night", "quarter").count { text.contains(it) } >= 2)
    }

    @Test fun voicePrintIsStableForSameSpeaker() {
        val half = speech.size / 2
        val a = models.voicePrint.embed(speech.copyOfRange(0, half))
        val b = models.voicePrint.embed(speech.copyOfRange(half, speech.size))
        assertNotNull(a); assertNotNull(b)
        val sim = VoiceMatch.cosine(a!!, b!!)
        val noise = models.voicePrint.embed(FloatArray(half) { (Random.nextFloat() - 0.5f) * 0.2f })!!
        val simNoise = VoiceMatch.cosine(a, noise)
        android.util.Log.i("VoiceBeamTest", "same=$sim noise=$simNoise")
        assertTrue("same speaker sim $sim vs noise $simNoise", sim > simNoise + 0.2f)
    }

    @Test fun exportsCleanAudioToM4a() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File(ctx.cacheDir, "x.wav"); val m4a = File(ctx.cacheDir, "x.m4a")
        WavWriter(wav, 16000).use { it.write(speech) }
        MediaExporter.wavToM4a(wav, m4a)
        assertTrue(m4a.length() > 5000)
        val ex = android.media.MediaExtractor().apply { setDataSource(m4a.absolutePath) }
        assertEquals(1, ex.trackCount)
        val f = ex.getTrackFormat(0)
        assertEquals("audio/mp4a-latm", f.getString(android.media.MediaFormat.KEY_MIME))
        val durUs = f.getLong(android.media.MediaFormat.KEY_DURATION)
        val expected = speech.size * 1_000_000L / 16000
        assertTrue("duration $durUs vs $expected", kotlin.math.abs(durUs - expected) < 300_000)
        ex.release()
    }

    private fun rms(a: FloatArray): Float { var e = 0f; for (v in a) e += v * v; return kotlin.math.sqrt(e / a.size) }
}
