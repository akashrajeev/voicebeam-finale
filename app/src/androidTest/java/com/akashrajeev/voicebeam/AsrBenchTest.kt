package com.akashrajeev.voicebeam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.core.WavWriter
import com.akashrajeev.voicebeam.core.VoiceMatch
import com.akashrajeev.voicebeam.ml.AudioModels
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Held-out public read speech and synthetic distractors, with exact reference text. */
@RunWith(AndroidJUnit4::class)
class AsrBenchTest {
    @Test fun perScenarioWordErrors() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val src = InstrumentationRegistry.getInstrumentation().context.assets
        val manifest = JSONArray(src.open("asr_bench/manifest.json").bufferedReader().use { it.readText() })
        val models = AudioModels.load(ctx.assets)
        val totals = linkedMapOf<String, IntArray>()
        for (i in 0 until manifest.length()) {
            val f = manifest.getJSONObject(i)
            val file = File(ctx.cacheDir, "asr-bench.wav")
            src.open("asr_bench/${f.getString("wav")}").use { input -> file.outputStream().use { input.copyTo(it) } }
            val (pcm, rate) = WavWriter.read(file)
            assertEquals(16000, rate)
            models.asr.resetStream()
            val result = StringBuilder()
            var partial = ""
            // 100 ms blocks as in production; enough trailing silence to endpoint.
            val samples = pcm + FloatArray(16000)
            for (off in samples.indices step 1600) {
                val end = minOf(samples.size, off + 1600)
                val (text, done) = models.asr.accept(samples.copyOfRange(off, end))
                if (done) { if (text.isNotBlank()) result.append(text).append(' '); partial = "" }
                else partial = text
            }
            result.append(partial)
            val ref = f.getString("reference")
            val hyp = result.toString().trim()
            val e = errors(ref, hyp)
            val scenario = f.getString("scenario")
            val total = totals.getOrPut(scenario) { IntArray(2) }
            total[0] += e; total[1] += tokens(ref).size
            android.util.Log.i("VoiceBeamWER", "case=${f.getString("id")} scenario=$scenario errors=$e words=${tokens(ref).size} hyp=$hyp")
        }
        for ((scenario, t) in totals) android.util.Log.i("VoiceBeamWER", "SCENARIO $scenario errors=${t[0]} words=${t[1]} WER=${String.format(Locale.US,"%.3f",t[0].toDouble()/t[1])}")
    }

    @Test fun voiceprintAcrossHeldOutSpeakers() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val src = InstrumentationRegistry.getInstrumentation().context.assets
        val models = AudioModels.load(ctx.assets)
        fun embed(id: String): FloatArray {
            val file = File(ctx.cacheDir, "voiceprint-bench.wav")
            src.open("asr_bench/$id.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
            val (pcm, rate) = WavWriter.read(file)
            assertEquals(16000, rate)
            return models.voicePrint.embed(pcm.copyOfRange(0, minOf(pcm.size, 16000 * 3)))
                ?: error("No voice embedding for $id")
        }
        val enrollment = embed("1089-134686-0013")
        for ((id, relation) in listOf(
            "1089-134686-0002" to "same-speaker",
            "121-121726-0008" to "different-speaker",
            "1221-135767-0005" to "different-speaker",
            "1089-134686-0002-other-speaker" to "overlap-25pct",
            "1089-134686-0002-strong-overlap" to "overlap-55pct",
        )) {
            val cosine = VoiceMatch.cosine(enrollment, embed(id))
            val gateScore = VoiceMatch.score(cosine)
            android.util.Log.i("VoiceBeamWER", "VOICEPRINT $relation case=$id cosine=$cosine gateScore=$gateScore")
        }
    }

    private fun tokens(s: String) = Regex("[A-Z0-9]+").findAll(s.uppercase(Locale.US)).map { it.value }.toList()
    private fun errors(reference: String, hypothesis: String): Int {
        val a = tokens(reference); val b = tokens(hypothesis)
        var prev = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val row = IntArray(b.size + 1); row[0] = i + 1
            for (j in b.indices) row[j+1] = minOf(row[j]+1, prev[j+1]+1, prev[j]+if (a[i]==b[j]) 0 else 1)
            prev = row
        }
        return prev[b.size]
    }
}
