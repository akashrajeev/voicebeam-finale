package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.separation.PassthroughExtractor
import com.akashrajeev.voicebeam.separation.TargetExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TargetExtractorContractTest {
    @Test fun passthroughKeepsLengthAndSamples() {
        val x: TargetExtractor = PassthroughExtractor()
        val frame = FloatArray(160) { it / 160f }
        val out = x.process(frame, TargetExtractor.Cue())
        assertEquals(frame.size, out.size)
        assertSame(frame, out)
        assertEquals(0, x.latencyMs)
    }

    @Test fun resetIsSafeAnyTime() {
        val x = PassthroughExtractor()
        x.reset(); x.process(FloatArray(10), TargetExtractor.Cue(lockedLips = .5f)); x.reset()
    }
}
