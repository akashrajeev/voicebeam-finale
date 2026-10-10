package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.EnhancerRegistry
import com.akashrajeev.voicebeam.core.EnhancerSlot
import com.akashrajeev.voicebeam.core.SpeechEnhancer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancerSlotTest {
    private class Fake(override val id: String, override val frameShift: Int = 256, val fn: (FloatArray) -> FloatArray = { it }) : SpeechEnhancer {
        var resets = 0; var released = false; var calls = 0
        override fun reset() { resets++ }
        override fun process(frame: FloatArray): FloatArray { calls++; return fn(frame) }
        override fun release() { released = true }
    }
    private val frame = FloatArray(256) { 0.1f }

    @Test fun noPrimaryUsesFallback() {
        val fb = Fake("gtcrn") { FloatArray(it.size) { 0.5f } }
        val slot = EnhancerSlot(fb)
        assertTrue(slot.usingFallback)
        assertEquals(0.5f, slot.process(frame)[0], 0f)
        assertEquals("gtcrn", slot.activeId)
    }

    @Test fun primaryOutputFeedsDownstream() {
        val fb = Fake("gtcrn"); val pr = Fake("vendor") { FloatArray(it.size) { 0.9f } }
        val slot = EnhancerSlot(fb, pr)
        assertEquals(0.9f, slot.process(frame)[0], 0f)
        assertEquals(0, fb.calls)
        assertEquals("vendor", slot.activeId)
    }

    @Test fun repeatedErrorsDropPrimaryAndBumpGeneration() {
        val fb = Fake("gtcrn") { FloatArray(it.size) { 0.5f } }; val pr = Fake("vendor") { error("boom") }
        val events = mutableListOf<String>()
        val slot = EnhancerSlot(fb, pr, onEvent = { events += it })
        val g0 = slot.generation
        assertEquals(0.1f, slot.process(frame)[0], 0f)   // 1st failure: raw pass-through
        assertEquals(0.1f, slot.process(frame)[0], 0f)   // 2nd
        assertEquals(0.5f, slot.process(frame)[0], 0f)   // 3rd: dropped, fallback output
        assertTrue(slot.usingFallback); assertTrue(pr.released); assertEquals(g0 + 1, slot.generation)
        assertEquals(1, fb.resets)
        assertTrue(events.any { it.startsWith("enhancer_dropped") })
        assertEquals(0.5f, slot.process(frame)[0], 0f)
        assertEquals(g0 + 1, slot.generation)
    }

    @Test fun goodFrameResetsFailureCount() {
        var n = 0
        val pr = Fake("vendor") { if (++n % 3 == 0) error("x") else it }
        val slot = EnhancerSlot(Fake("gtcrn"), pr)
        repeat(30) { slot.process(frame) }
        assertFalse(slot.usingFallback)
    }

    @Test fun nanOutputCountsAsFailure() {
        val pr = Fake("vendor") { FloatArray(it.size) { Float.NaN } }
        val slot = EnhancerSlot(Fake("gtcrn"), pr)
        repeat(3) { slot.process(frame) }
        assertTrue(slot.usingFallback)
    }

    @Test fun oversizedOutputCountsAsFailure() {
        val pr = Fake("vendor") { FloatArray(it.size + 1) }
        val slot = EnhancerSlot(Fake("gtcrn"), pr)
        repeat(3) { slot.process(frame) }
        assertTrue(slot.usingFallback)
    }

    @Test fun shortOutputIsAllowedForWarmup() {
        val pr = Fake("vendor") { FloatArray(0) }
        val slot = EnhancerSlot(Fake("gtcrn"), pr)
        repeat(20) { assertEquals(0, slot.process(frame).size) }
        assertFalse(slot.usingFallback)
    }

    @Test fun slowerThanRealTimeIsDropped() {
        var t = 0L
        val pr = Fake("vendor") { t += 20_000_000L; it }   // 20 ms per 16 ms frame
        val slot = EnhancerSlot(Fake("gtcrn"), pr, clockNs = { t })
        repeat(7) { slot.process(frame) }
        assertFalse(slot.usingFallback)
        slot.process(frame)
        assertTrue(slot.usingFallback)
    }

    @Test fun fastEnoughStays() {
        var t = 0L
        val pr = Fake("vendor") { t += 5_000_000L; it }
        val slot = EnhancerSlot(Fake("gtcrn"), pr, clockNs = { t })
        repeat(100) { slot.process(frame) }
        assertFalse(slot.usingFallback)
    }

    @Test fun mismatchedFrameSizeIsRejectedUpFront() {
        val pr = Fake("vendor", frameShift = 160)
        val slot = EnhancerSlot(Fake("gtcrn"), pr)
        assertTrue(slot.usingFallback); assertTrue(pr.released)
    }

    @Test fun resetFailureDropsPrimary() {
        val pr = object : SpeechEnhancer { override val id = "v"; override val frameShift = 256
            override fun reset() { error("no") }; override fun process(frame: FloatArray) = frame; override fun release() {} }
        val slot = EnhancerSlot(Fake("gtcrn"), pr)
        slot.reset()
        assertTrue(slot.usingFallback)
    }

    @Test fun registryDefaultAndUnknownGiveNull() {
        assertNull(EnhancerRegistry.create("gtcrn"))
        assertNull(EnhancerRegistry.create("nope"))
        EnhancerRegistry.register("boom") { error("x") }
        assertNull(EnhancerRegistry.create("boom"))
        val f = Fake("ok"); EnhancerRegistry.register("ok") { f }
        assertSame(f, EnhancerRegistry.create("ok"))
    }
}
