package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.*
import com.akashrajeev.voicebeam.engine.CaptionAssembler
import org.junit.Assert.*
import org.junit.Test

class LabLogicTest {
    @Test fun otherVisualSpeakerVetoesFalseVoiceMatch() {
        val gate = TargetGate()
        repeat(5) { gate.targetProbability(GateInputs(true, 0f, 0.9f, 1f, true)) } // settle hysteresis
        val p = gate.targetProbability(GateInputs(true, 0f, 0.9f, 1f, true))
        assertEquals(TargetState.OTHER, gate.state)
        assertEquals(0f, p, 0f)
    }
    @Test fun overlapIsNotPretendedToBeIsolated() {
        val gate = TargetGate()
        repeat(5) { gate.targetProbability(GateInputs(true, 0.9f, 0.9f, 1f, true)) } // settle hysteresis
        assertEquals(0.5f, gate.targetProbability(GateInputs(true, 0.9f, 0.9f, 1f, true)), 0f)
        assertEquals(TargetState.OVERLAP, gate.state)
    }
    @Test fun utterancePreservesPreRollAndHasBoundedEndpoint() {
        val b = UtteranceBuffer(1000)
        repeat(10) { assertNull(b.accept(FloatArray(100) { 0.1f }, false)) }
        repeat(9) { assertNull(b.accept(FloatArray(100) { 0.3f }, true)) }
        val update = b.accept(FloatArray(100) { 0.3f }, true)!!
        assertFalse(update.ended); assertEquals(2000, update.samples.size)
        repeat(5) { assertNull(b.accept(FloatArray(100), false)) }
        val end = b.accept(FloatArray(100), false)!!
        assertTrue(end.ended); assertEquals(2600, end.samples.size)
        b.reset(); assertNull(b.accept(FloatArray(100), false))
    }
    @Test fun queueDropCannotCompressCaptionTimeline() {
        val a = CaptionAssembler(1000)
        a.advanceTo(10000)
        val s = a.onBlock(1000, "Hello", true, 0.9f, true)!!
        assertEquals(11000L, s.endMs)
        a.advanceTo(5000); assertEquals(11000L, a.nowMs)
    }
}
