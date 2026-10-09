package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.*
import org.junit.Assert.*
import org.junit.Test

class SuppressionTest {
    private val target = GateInputs(true, 0.9f, 0f, null, true)
    private val self = GateInputs(true, 0f, 0f, null, true)

    @Test fun staleTargetProbabilityExpiresAfterVadSilence() {
        val g = TargetGate(frameMs = 10f)
        repeat(100) { g.process(target) }
        repeat(150) { g.process(self.copy(voiceActive = false)) }
        assertEquals(0f, g.probability, 0f)
        assertFalse(g.boostAllowed)
        assertTrue(g.gain in 0.55f..0.65f) // UNCERTAIN ducked
    }
    @Test fun uncertainVoiceGetsNoBoostButDoesNotDisappear() {
        val g = TargetGate(frameMs = 10f)
        repeat(100) { g.process(target) }
        repeat(100) { g.process(self) }
        assertEquals(TargetState.UNCERTAIN, g.state)
        assertFalse(g.boostAllowed)
        assertTrue(g.gain in 0.55f..0.65f) // UNCERTAIN ducked
    }
    @Test fun conflictingNegativeAndLipMotionIsUncertain() {
        val g = TargetGate()
        repeat(5) { g.targetProbability(target.copy(voiceMatch = 0.1f)) } // settle hysteresis
        assertEquals(0f, g.targetProbability(target.copy(voiceMatch = 0.1f)), 0f)
        assertEquals(TargetState.UNCERTAIN, g.state)
    }
    @Test fun fullyQuietOthersKeepsSafetyFloorNotBoostedResidual() {
        val g = TargetGate(frameMs = 10f); g.quietOthers = 1f
        repeat(100) { g.process(target) }
        repeat(150) { g.process(self.copy(voiceMatch = 0.1f)) }
        assertEquals(.02f,g.gain,.0001f); assertFalse(g.boostAllowed)
    }
    @Test fun confirmedTargetStillPassesAndCanBeBoosted() {
        val g = TargetGate(frameMs = 10f)
        repeat(100) { g.process(target) }
        assertTrue(g.gain > 0.95f); assertTrue(g.boostAllowed)
    }
    @Test fun newlyLockedQuietFaceIsUnboostedPassthrough() {
        val g = TargetGate(); repeat(50) { g.process(self.copy(hasLock = false)) }
        repeat(50) { g.process(self) } // lock, then settle hysteresis + gain to UNCERTAIN duck
        assertTrue(g.gain in 0.55f..0.70f); assertFalse(g.boostAllowed) // UNCERTAIN ducked ~0.608, no boost
    }
    @Test fun overlapIsNotGivenTargetBoost() {
        val g = TargetGate(); repeat(5) { g.process(target.copy(othersSpeaking = 0.9f)) } // settle hysteresis
        assertEquals(TargetState.OVERLAP, g.state); assertFalse(g.boostAllowed)
    }
    @Test fun incompleteEnrollmentNeverMutesOrBoosts() {
        val g = TargetGate(); g.quietOthers = 1f
        repeat(100) { g.process(target.copy(voiceLearned = false, voiceMatch = 0.01f)) }
        assertEquals(TargetState.UNCERTAIN, g.state); assertTrue(g.gain in 0.48f..0.54f); assertFalse(g.boostAllowed) // UNCERTAIN ducked at strength 1.0
    }
    @Test fun ambiguousMatchDoesNotVetoVisibleTarget() {
        val g = TargetGate()
        repeat(100) { g.process(target.copy(voiceMatch = 0.5f)) }
        assertEquals(TargetState.TARGET, g.state); assertTrue(g.gain > 0.95f)
    }
    @Test fun overlapPreservesBothVoicesWithoutBoost() {
        val g = TargetGate(); repeat(100) { g.process(target.copy(othersSpeaking = 0.9f)) }
        assertTrue(g.gain in 0.45f..0.55f); assertFalse(g.boostAllowed) // OVERLAP ducked to 0.5
    }
    @Test fun zeroSuppressionStillHonorsUserSlider() {
        val g = TargetGate(frameMs = 10f); g.quietOthers = 0f
        repeat(100) { g.process(self) }
        assertTrue(g.gain > 0.99f); assertFalse(g.boostAllowed)
    }
}
