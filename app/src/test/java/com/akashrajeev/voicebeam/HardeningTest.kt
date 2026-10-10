package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.DropOldestQueue
import com.akashrajeev.voicebeam.core.FrameDsp
import com.akashrajeev.voicebeam.core.ListenLifecycle
import com.akashrajeev.voicebeam.core.ListenPhase
import com.akashrajeev.voicebeam.core.SessionIds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import kotlin.math.abs

class HardeningTest {

    @Test fun peakSafeBoostAvoidsHardClipping() {
        val clean = floatArrayOf(.4f, -.8f, .2f)
        val boost = FrameDsp.safeBoost(clean, 3, 1f, 4f)
        assertEquals(1.1875f, boost, .0001f)
        val gated = FloatArray(3); val out = FloatArray(3)
        FrameDsp.gateAndBoost(clean, 3, 1f, boost, gated, out)
        assertTrue(out.all { abs(it) <= .95001f })
    }
    @Test fun queueKeepsOrderAndDropsOldestOnOverload() {
        val q = DropOldestQueue<Int>(3)
        assertFalse(q.offer(1)); assertFalse(q.offer(2)); assertFalse(q.offer(3))
        assertTrue(q.offer(4))             // 1 dropped
        assertTrue(q.offer(5))             // 2 dropped
        assertEquals(2L, q.dropped)
        assertEquals(3, q.size)
        assertEquals(3, q.poll(10)); assertEquals(4, q.poll(10)); assertEquals(5, q.poll(10))
        assertNull(q.poll(5))              // empty: times out
    }

    @Test fun queueBacklogIsBoundedUnderSlowConsumer() {
        val q = DropOldestQueue<Int>(96)
        repeat(1000) { q.offer(it) }
        assertEquals(96, q.size)
        assertEquals(1000L - 96, q.dropped)
        assertEquals(1000 - 96, q.poll(10))   // oldest kept is the first of the last 96, in order
    }

    @Test fun queueWakesBlockedConsumer() {
        val q = DropOldestQueue<String>(4)
        var got: String? = null
        val t = Thread { got = q.poll(2000) }
        t.start(); Thread.sleep(50); q.offer("x"); t.join(2000)
        assertEquals("x", got)
    }

    @Test fun queueClearEmpties() {
        val q = DropOldestQueue<Int>(2); q.offer(1); q.clear()
        assertEquals(0, q.size)
    }

    @Test fun dspMatchesReferenceMath() {
        val input = FloatArray(256) { (it % 17 - 8) / 10f }
        val denoised = FloatArray(250) { (it % 13 - 6) / 9f }
        val n = denoised.size
        val mix = 0.7f
        val clean = FloatArray(256)
        FrameDsp.mixDenoised(denoised, input, mix, clean, n)
        val refClean = FloatArray(n) { k -> mix * denoised[k] + (1f - mix) * input[input.size - n + k] }
        assertArrayEquals(refClean, clean.copyOf(n), 0f)

        val gated = FloatArray(256); val boosted = FloatArray(256)
        val e = FrameDsp.gateAndBoost(clean, n, 0.5f, 8f, gated, boosted)
        var refE = 0f
        for (k in 0 until n) {
            val g = refClean[k] * 0.5f
            assertEquals(g, gated[k], 0f)
            assertEquals((g * 8f).coerceIn(-1f, 1f), boosted[k], 0f)
            refE += refClean[k] * refClean[k]
        }
        assertEquals(refE, e, 1e-4f)
        assertTrue(boosted.take(n).all { abs(it) <= 1f })
    }

    @Test fun dspReusedBuffersDoNotLeakBetweenFrames() {
        val clean = FloatArray(4); val gated = FloatArray(4); val boosted = FloatArray(4)
        FrameDsp.mixDenoised(floatArrayOf(1f, 1f, 1f, 1f), FloatArray(4), 1f, clean, 4)
        FrameDsp.gateAndBoost(clean, 4, 1f, 1f, gated, boosted)
        FrameDsp.mixDenoised(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f, 0f, 0f), 1f, clean, 2)
        val e = FrameDsp.gateAndBoost(clean, 2, 1f, 1f, gated, boosted)
        assertEquals(0f, e, 0f)
        assertEquals(0f, gated[0], 0f); assertEquals(0f, gated[1], 0f)
    }

    @Test fun lifecycleIsIdempotentAndOrdered() {
        val l = ListenLifecycle()
        assertEquals(ListenPhase.IDLE, l.phase)
        assertFalse(l.beginStop())                 // nothing to stop
        assertTrue(l.beginStart()); assertFalse(l.beginStart())
        assertTrue(l.finishStart()); assertTrue(l.isListening)
        assertFalse(l.beginStart())                // already listening
        assertTrue(l.beginStop()); assertFalse(l.beginStop())
        assertFalse(l.beginStart())                // refused while stopping
        l.finishStop()
        assertEquals(ListenPhase.IDLE, l.phase)
        assertTrue(l.beginStart())
    }

    @Test fun lifecycleAbortedStartReturnsToIdle() {
        val l = ListenLifecycle()
        l.beginStart(); l.abortStart()
        assertEquals(ListenPhase.IDLE, l.phase)
        assertTrue(l.beginStart())
        l.finishStart(); l.abortStart()            // abort is only for a start in progress
        assertTrue(l.isListening)
    }

    @Test fun sessionIdsNeverCollide() {
        val now = Date(1_700_000_000_000L)
        val taken = mutableSetOf<String>()
        val ids = (1..5).map { SessionIds.next(now) { id -> id in taken }.also { taken.add(it) } }
        assertEquals(5, ids.toSet().size)
        assertTrue(ids[0].matches(Regex("\\d{8}-\\d{6}")))
        assertEquals(ids[0] + "-2", ids[1])
        assertEquals(ids[0] + "-3", ids[2])
    }
}
