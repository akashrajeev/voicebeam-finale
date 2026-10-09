package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.*
import org.junit.Assert.*
import org.junit.Test

class DiagnosticHardeningTest {
    @Test fun diagnosticConstructionFailureDoesNotStopPrimaryWork() {
        var errors = 0
        val d = OptionalDiagnostic<Any>({ error("constructor") }, {}, { errors++ })
        assertNull(d.sample { true })
        assertNull(d.sample { true })
        assertEquals(1, errors)
        val gate = TargetGate()
        repeat(5) { gate.process(GateInputs(true, .9f, 0f, null, true)) } // settle hysteresis
        assertEquals(TargetState.TARGET, gate.state)
    }
    @Test fun inferenceFailureDisablesDiagnosticAndReleasesOnce() {
        var builds = 0; var releases = 0
        val d = OptionalDiagnostic({ builds++; Any() }, { releases++ }, { error("logging") })
        assertNull(d.sample<Boolean> { error("inference") })
        assertNull(d.sample { true })
        d.close()
        assertEquals(1, builds); assertEquals(1, releases)
    }
    @Test fun diagnosticResetFailureIsNonFatal() {
        val d = OptionalDiagnostic({ Any() }, { error("release") }, {})
        assertNull(d.sample<Unit> { error("reset") })
        assertNull(d.sample { true })
    }
    @Test fun callbackGuardReleasesOnException() {
        var busy = true
        try {
            withCallbackCleanup(cleanup = { busy = false }) { error("onFaces") }
            fail("exception expected")
        } catch (_: IllegalStateException) {}
        assertFalse(busy)
    }
    @Test fun callbackGuardReleasesAfterProcessingNotBefore() {
        var busy = true
        withCallbackCleanup(cleanup = { busy = false }) { assertTrue(busy) }
        assertFalse(busy)
    }
    @Test fun rawSpeechReachesProductionGateAndQueryWhenCleanVadIsFalse() {
        val d = OptionalDiagnostic({ Any() }, {}, {})
        val cleanSpeech = d.sample { false }
        assertEquals(false, cleanSpeech)
        val observation = SpeechObservation.observe(false, true, .06f,
            GateInputs(true, .9f, 0f, null, false))
        val gate = TargetGate(); repeat(5) { gate.process(observation.inputs) } // settle hysteresis
        assertEquals(TargetState.TARGET, gate.state); assertTrue(gate.boostAllowed)
        val queue = DropOldestQueue<Pair<FloatArray, Float>>(2)
        val raw = floatArrayOf(.06f, .07f)
        SpeechObservation.enqueue(observation, raw, queue)
        raw[0] = 0f
        val queued = queue.poll(0)!!
        assertArrayEquals(floatArrayOf(.06f, .07f), queued.first, 0f)
        assertEquals(.9f, queued.second, 0f)
    }
    @Test fun fallbackFeedsQueryWithoutOverridingFalseRawVad() {
        val o = SpeechObservation.observe(false, false, .06f,
            GateInputs(true, .9f, 0f, null, false))
        val g = TargetGate(); repeat(5) { g.process(o.inputs) } // settle hysteresis
        val q = DropOldestQueue<Pair<FloatArray, Float>>(2)
        SpeechObservation.enqueue(o, floatArrayOf(.06f), q)
        assertEquals(1, q.size); assertEquals(TargetState.UNCERTAIN, g.state)
        assertFalse(g.boostAllowed)
    }
    @Test fun freshnessBoundaryIs399Not400Milliseconds() {
        assertTrue(SpeechObservation.visionFresh(1000, 601))
        assertFalse(SpeechObservation.visionFresh(1000, 600))
    }
}
