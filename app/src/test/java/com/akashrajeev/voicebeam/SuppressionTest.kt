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
        assertTrue(g.gain > 0.99f)
    }
    @Test fun uncertainVoiceGetsNoBoostButDoesNotDisappear() {
        val g = TargetGate(frameMs = 10f)
        repeat(100) { g.process(target) }
        repeat(100) { g.process(self) }
        assertEquals(TargetState.UNCERTAIN, g.state)
        assertFalse(g.boostAllowed)
        assertTrue(g.gain > 0.99f)
    }
    @Test fun conflictingNegativeAndLipMotionIsUncertain() {
        val g = TargetGate()
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
        assertTrue(g.process(self) > 0.99f); assertFalse(g.boostAllowed)
    }
    @Test fun overlapIsNotGivenTargetBoost() {
        val g = TargetGate(); g.process(target.copy(othersSpeaking = 0.9f))
        assertEquals(TargetState.OVERLAP, g.state); assertFalse(g.boostAllowed)
    }
    @Test fun incompleteVisibleEnrollmentPassesWithProvisionalBoost() {
        val g = TargetGate(); g.quietOthers = 1f
        repeat(100) { g.process(target.copy(voiceLearned = false, voiceMatch = 0.01f)) }
        assertEquals(TargetState.UNCERTAIN, g.state); assertTrue(g.gain > 0.99f); assertTrue(g.boostAllowed)
    }
    @Test fun ambiguousMatchDoesNotVetoVisibleTarget() {
        val g = TargetGate()
        repeat(100) { g.process(target.copy(voiceMatch = 0.5f)) }
        assertEquals(TargetState.TARGET, g.state); assertTrue(g.gain > 0.95f)
    }
    @Test fun overlapPreservesBothVoicesWithoutBoost() {
        val g = TargetGate(); repeat(100) { g.process(target.copy(othersSpeaking = 0.9f)) }
        assertTrue(g.gain > 0.99f); assertFalse(g.boostAllowed)
    }
    @Test fun zeroSuppressionStillHonorsUserSlider() {
        val g = TargetGate(frameMs = 10f); g.quietOthers = 0f
        repeat(100) { g.process(self) }
        assertTrue(g.gain > 0.99f); assertFalse(g.boostAllowed)
    }
}
