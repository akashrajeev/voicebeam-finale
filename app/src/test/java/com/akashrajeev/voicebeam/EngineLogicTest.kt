package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.engine.CaptionAssembler
import com.akashrajeev.voicebeam.engine.VoiceLearner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLogicTest {

    @Test fun assemblerBuildsTimedSegmentsAndVotes() {
        val a = CaptionAssembler(16000)
        // 1 s silence, then 1 s of target speech, then endpoint.
        repeat(10) { assertNull(a.onBlock(1600, "", false, 0.1f, false)) }
        repeat(9) { assertNull(a.onBlock(1600, "Hello", false, 0.9f, true)) }
        val seg = a.onBlock(1600, "Hello there", true, 0.9f, true)
        assertNotNull(seg)
        seg!!
        assertEquals("Hello there", seg.text)
        assertTrue(seg.isTarget)
        assertEquals(600L, seg.startMs)     // 1000 ms minus 400 ms lead-in
        assertEquals(2000L, seg.endMs)
        assertEquals("", a.partial)
        // Next utterance is someone else.
        repeat(5) { a.onBlock(1600, "Wait", false, 0.1f, true) }
        val other = a.onBlock(1600, "Wait which lab", true, 0.1f, true)!!
        assertFalse(other.isTarget)
        // Empty endpoint yields nothing.
        assertNull(a.onBlock(1600, "", true, 0.5f, false))
    }

    @Test fun deliberateRawEnrollmentThenFrozenScoring() {
        var calls = 0
        val voiceA = floatArrayOf(1f, 0.1f, 0f)
        val voiceB = floatArrayOf(0f, 0.2f, 1f)
        var current = voiceA
        val l = VoiceLearner({ calls++; current }, sampleRate = 1000, chunkSeconds = 1f, needed = 2)
        val block = FloatArray(250){.02f}
        l.beginEnrollment()
        // Speech while lips are not moving: never enrolls.
        repeat(20) { l.feed(FloatArray(250), 0.1f) }
        assertEquals(0, calls); assertFalse(l.learned)
        // Lip-confirmed speech: 2 chunks of 1000 samples.
        repeat(8) { l.feed(block, 0.9f) }
        assertTrue(l.learned)
        // Scoring the same voice gives a high score; a different voice gives low.
        var s: Float? = null
        repeat(4) { l.feed(block, 0f)?.let { v -> s = v } }
        assertTrue((s ?: 0f) > 0.9f)
        current = voiceB
        s = null
        repeat(4) { l.feed(block, 0f)?.let { v -> s = v } }
        assertTrue((s ?: 1f) < 0.2f)
        l.reset(); assertFalse(l.learned)
    }
}
