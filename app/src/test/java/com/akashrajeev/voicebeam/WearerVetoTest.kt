package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.*
import org.junit.Assert.*
import org.junit.Test

class WearerVetoTest {
    private val target = GateInputs(true, 0.9f, 0f, 0.85f, true)
    @Test fun optionalAndRequiresBothScoresAndMargin() {
        val g=TargetGate()
        assertEquals(TargetState.TARGET, g.apply { targetProbability(target.copy(wearerMatch=1f)) }.state)
        g.targetProbability(target.copy(wearerMatch=1f, wearerVetoEnabled=true, voiceMatch=0.81f))
        assertEquals(TargetState.OTHER,g.state)
        g.targetProbability(target.copy(wearerMatch=0.95f, wearerVetoEnabled=true, voiceMatch=0.9f))
        assertEquals(TargetState.TARGET,g.state)
        g.targetProbability(target.copy(wearerMatch=1f, wearerVetoEnabled=true, voiceMatch=null))
        assertEquals(TargetState.TARGET,g.state)
    }
    @Test fun wearerVetoDoesNotAllowBoost() {
        val g=TargetGate()
        repeat(150) { g.process(target.copy(wearerMatch=1f, wearerVetoEnabled=true, voiceMatch=0.81f)) }
        assertEquals(TargetState.OTHER,g.state); assertFalse(g.boostAllowed); assertTrue(g.gain<0.041f)
    }
}
