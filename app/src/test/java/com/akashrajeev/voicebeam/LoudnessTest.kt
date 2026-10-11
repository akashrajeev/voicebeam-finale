package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class LoudnessTest {
    private val sr = 16000
    private val labels = Array(8) { if (it < 4) Seg.TARGET_ONLY else Seg.OTHER_ONLY }
    private val n = sr * 4
    private val speech = BooleanArray((n + 511) / 512) { true }
    private fun db(x: FloatArray, a: Int, b: Int): Float { var s = 0.0; for (i in a until b) s += x[i].toDouble() * x[i]; return (10 * log10(s / (b - a) + 1e-12)).toFloat() }
    private fun tone(amp: Float) = FloatArray(n) { if (it < 2 * sr) (amp * sin(2 * PI * 500.0 * it / sr)).toFloat() else 0f }

    @Test fun quietSpeechIsBoostedTowardTarget() {
        val r = Loudness.normalize(tone(0.04f), speech, labels)                       // about -31 dBFS RMS
        assertTrue(r.gainDb > 8f); assertEquals(-20f, db(r.audio, 0, 2 * sr), 1.5f)
    }
    @Test fun gainIsClampedAndNeverClips() {
        val r = Loudness.normalize(tone(0.001f), speech, labels)
        assertEquals(Loudness.MAX_BOOST_DB, r.gainDb, 0.01f)
        val peaky = tone(0.05f); peaky[100] = 0.5f
        val p = Loudness.normalize(peaky, speech, labels)
        assertTrue(p.audio.maxOf { Math.abs(it) } <= 0.8913f + 1e-4f)               // -1 dBFS
    }
    @Test fun loudSpeechIsCutAtMostSixDb() {
        val r = Loudness.normalize(tone(0.9f), speech, labels)
        assertTrue(r.gainDb >= Loudness.MAX_CUT_DB - 0.01f && r.gainDb <= 0f)
    }
    @Test fun closeToTargetIsLeftAlone() {
        val r = Loudness.normalize(tone(0.141f), speech, labels)                      // about -20 dBFS RMS
        assertEquals(0f, r.gainDb, 0f); assertEquals(n, r.audio.size)
    }
    @Test fun muteAndOtherBinsDoNotInfluenceTheMeasurement() {
        val a = tone(0.02f); val loudOther = FloatArray(n) { if (it >= 2 * sr) 0.03f else a[it] }
        assertEquals(Loudness.normalize(a, speech, labels).gainDb, Loudness.normalize(loudOther, speech, labels).gainDb, 0.01f)
    }
    @Test fun noUsableSpeechLeavesAudioUnchanged() {
        val r = Loudness.normalize(tone(0.02f), BooleanArray(speech.size), labels)
        assertEquals(0f, r.gainDb, 0f); assertNull(r.measuredDb)
    }
    @Test fun pipelineShipsGainAfterGatesAndReportsIt() {
        val sl = arrayOf(Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.TARGET_ONLY, Seg.OTHER_ONLY, Seg.OTHER_ONLY, Seg.NONE, Seg.NONE)
        fun t(hz: Double, a: Float, i: Int) = (a * sin(2 * PI * hz * i / sr)).toFloat()
        val src = FloatArray(n) { i -> when (sl[i / (sr / 2)]) { Seg.TARGET_ONLY -> t(500.0, 0.03f, i); Seg.OTHER_ONLY -> t(1200.0, 0.03f, i); else -> t(300.0, 0.0005f, i) } }
        val ext = FloatArray(n) { if (sl[it / (sr / 2)] == Seg.TARGET_ONLY) t(500.0, 0.028f, it) else 0f }
        val voiced = BooleanArray((n + 511) / 512) { f -> sl[minOf(7, (f * 512 + 256) / (sr / 2))] != Seg.NONE }
        val on = FootageRenderPipeline.run(src, ext, null, sl, voiced, normalizeLoudness = true) as RenderOutcome.Rendered
        val off = FootageRenderPipeline.run(src, ext, null, sl, voiced) as RenderOutcome.Rendered
        assertTrue(on.gainDb > 5f); assertEquals(0f, off.gainDb, 0f)
        assertEquals(off.gates.retentionDb, on.gates.retentionDb, 1e-4f)             // gates see the pre-gain signal either way
        for (i in sr * 2 until sr * 3) assertEquals(0f, on.audio[i], 0f)             // muted stays exact zero
    }
}
