package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.*
import org.junit.Assert.*
import org.junit.Test

class WearerVetoTest {
    private val target = GateInputs(true, 0.9f, 0f, 0.85f, true)
    @Test fun optionalAndRequiresBothScoresAndMargin() {
        val g=TargetGate()
        repeat(5) { g.targetProbability(target.copy(wearerMatch=1f)) } // settle: TARGET
        assertEquals(TargetState.TARGET, g.state)
        repeat(5) { g.targetProbability(target.copy(wearerMatch=1f, wearerVetoEnabled=true, voiceMatch=0.81f)) } // settle: OTHER
        assertEquals(TargetState.OTHER,g.state)
        repeat(5) { g.targetProbability(target.copy(wearerMatch=0.95f, wearerVetoEnabled=true, voiceMatch=0.9f)) } // settle: TARGET
        assertEquals(TargetState.TARGET,g.state)
        repeat(5) { g.targetProbability(target.copy(wearerMatch=1f, wearerVetoEnabled=true, voiceMatch=null)) } // settle: TARGET
        assertEquals(TargetState.TARGET,g.state)
    }
    @Test fun wearerVetoDoesNotAllowBoost() {
        val g=TargetGate()
        repeat(150) { g.process(target.copy(wearerMatch=1f, wearerVetoEnabled=true, voiceMatch=0.81f)) }
        assertEquals(TargetState.OTHER,g.state); assertFalse(g.boostAllowed); assertTrue(g.gain<0.25f) // linear residual at 0.8
    }
}
