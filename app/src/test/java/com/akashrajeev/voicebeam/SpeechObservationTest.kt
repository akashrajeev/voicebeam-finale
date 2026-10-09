package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.*
import org.junit.Assert.*
import org.junit.Test

class SpeechObservationTest {
    private fun target() = GateInputs(true, .9f, 0f, null, false)
    @Test fun querySurvivesDenoisedVadFailure() {
        assertTrue(SpeechObservation.queryEligible(false, false, .06f, target()))
    }
    @Test fun rawSpeechDoesNotRequireLipMovement() {
        assertTrue(SpeechObservation.queryEligible(false, true, .06f, target().copy(lockedSpeaking=0f)))
    }
    @Test fun fallbackRejectsSilenceOtherAndStaleVision() {
        assertFalse(SpeechObservation.queryEligible(false, false, 0f, target()))
        assertFalse(SpeechObservation.queryEligible(false, false, .06f, target().copy(othersSpeaking=.9f)))
        assertFalse(SpeechObservation.queryEligible(false, false, .06f, target().copy(lockedVisible=false)))
    }
    @Test fun enrollmentRemainsUnconditional() {
        assertTrue(SpeechObservation.queryEligible(true, false, 0f, target()))
    }
    @Test fun callbackCannotRefreshAnOldSourceFrame() {
        assertFalse(SpeechObservation.visionFresh(1500, 1000))
        assertTrue(SpeechObservation.visionFresh(1500, 1200))
        assertFalse(SpeechObservation.visionFresh(1500, 1600))
    }
    @Test fun lipsAloneNeverForceTargetBoost() {
        val g=TargetGate()
        repeat(5){g.process(target())} // settle hysteresis: UNCERTAIN
        assertEquals(TargetState.UNCERTAIN,g.state);assertFalse(g.boostAllowed)
        repeat(5){g.process(target().copy(voiceActive=true))} // settle hysteresis: TARGET
        assertEquals(TargetState.TARGET,g.state);assertTrue(g.boostAllowed)
    }
}
