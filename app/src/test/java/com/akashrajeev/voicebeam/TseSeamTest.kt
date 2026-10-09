package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.TseStage
import com.akashrajeev.voicebeam.separation.PassthroughExtractor
import com.akashrajeev.voicebeam.separation.TargetExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TseSeamTest {
    @Test fun disabledReturnsInputUntouched() {
        val stage = TseStage(enabled = false)
        val frame = FloatArray(160) { it / 160f }
        assertSame(frame, stage.apply(frame, TargetExtractor.Cue()))
        assertEquals(0L, stage.fallbackCount)
    }

    @Test fun enabledPassthroughKeepsSamples() {
        val stage = TseStage(enabled = true, extractor = PassthroughExtractor())
        val frame = FloatArray(160) { it / 160f }
        assertSame(frame, stage.apply(frame, TargetExtractor.Cue(lockedLips = .7f)))
        assertEquals(0L, stage.fallbackCount)
    }

    @Test fun throwingExtractorFallsBackToInput() {
        val bad = object : TargetExtractor {
            override val name = "bad"
            override val latencyMs = 5
            override fun process(frame: FloatArray, cue: TargetExtractor.Cue): FloatArray = throw RuntimeException("boom")
            override fun reset() = throw RuntimeException("boom")
        }
        val stage = TseStage(enabled = true, extractor = bad)
        val frame = FloatArray(160) { 0.1f }
        assertSame(frame, stage.apply(frame, TargetExtractor.Cue()))
        assertEquals(1L, stage.fallbackCount)
        stage.reset() // reset must never throw either
        assertEquals(1L, stage.fallbackCount)
    }

    @Test fun wrongSizeOutputFallsBackToInput() {
        val short = object : TargetExtractor {
            override val name = "short"
            override val latencyMs = 5
            override fun process(frame: FloatArray, cue: TargetExtractor.Cue): FloatArray = FloatArray(frame.size - 1)
            override fun reset() {}
        }
        val stage = TseStage(enabled = true, extractor = short)
        val frame = FloatArray(160) { 0.1f }
        assertSame(frame, stage.apply(frame, TargetExtractor.Cue()))
        assertEquals(1L, stage.fallbackCount)
    }

    @Test fun cueReachesExtractor() {
        var seen: TargetExtractor.Cue? = null
        val probe = object : TargetExtractor {
            override val name = "probe"
            override val latencyMs = 3
            override fun process(frame: FloatArray, cue: TargetExtractor.Cue): FloatArray {
                seen = cue
                return frame
            }
            override fun reset() {}
        }
        val stage = TseStage(enabled = true, extractor = probe)
        val embedding = floatArrayOf(0.2f, 0.4f)
        stage.apply(FloatArray(8), TargetExtractor.Cue(voiceEmbedding = embedding, lockedLips = 0.6f))
        assertEquals(0.6f, seen!!.lockedLips!!)
        assertSame(embedding, seen!!.voiceEmbedding)
    }
}
