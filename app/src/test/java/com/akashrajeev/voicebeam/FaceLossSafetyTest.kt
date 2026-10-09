package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.*
import org.junit.Assert.*
import org.junit.Test

class FaceLossSafetyTest {
    private val face = FaceObservation(Box(.2f, .2f, .6f, .7f), .03f)
    @Test fun expiredFaceKeepsIntentButCannotBeReassigned() {
        val tracker = FaceTracker()
        tracker.update(1000, listOf(face))
        val id = tracker.lockAt(.4f, .4f, 1000)
        tracker.update(7001, emptyList())
        assertEquals(id, tracker.lockedId)
        assertTrue(tracker.snapshot(7001).isEmpty())
        tracker.update(7100, listOf(face))
        assertFalse(tracker.snapshot(7100).any { it.id == id })
        assertEquals(id, tracker.lockedId)
        assertNotEquals(id, tracker.lockAt(.4f, .4f, 7100))
        tracker.unlock(); assertNull(tracker.lockedId)
    }
    @Test fun returningFaceAfterLongGapCannotInheritOldIdWithoutRetap() {
        val tracker = FaceTracker()
        tracker.update(1000, listOf(face))
        val id = tracker.lockAt(.4f, .4f, 1000)
        tracker.update(8000, listOf(face))
        assertEquals(id, tracker.lockedId)
        assertFalse(tracker.snapshot(8000).any { it.id == id })
    }
    @Test fun missingTargetIgnoresStrongNegativeAndPositiveScores() {
        for (match in listOf(0f, 1f)) {
            val gate = TargetGate()
            repeat(50) { gate.process(GateInputs(true, 0f, .9f, match, true, lockedVisible = false)) } // settle hysteresis + gain
            assertEquals(TargetState.UNCERTAIN, gate.state)
            assertFalse(gate.boostAllowed)
            assertTrue(gate.gain in 0.55f..0.70f) // UNCERTAIN ducked, not 1.0
        }
    }
    @Test fun faceLossCancelsTargetBoostHoldImmediately() {
        val gate = TargetGate()
        repeat(5) { gate.process(GateInputs(true, .9f, 0f, 1f, true)) } // settle hysteresis: TARGET
        assertTrue(gate.boostAllowed)
        repeat(5) { gate.process(GateInputs(true, 0f, 0f, 1f, false, lockedVisible = false)) } // settle: UNCERTAIN
        assertEquals(TargetState.UNCERTAIN, gate.state)
        assertFalse(gate.boostAllowed)
    }
    @Test fun explicitUnlockStillPermitsGeneralMonitor() {
        val gate = TargetGate()
        gate.process(GateInputs(false, 0f, 0f, null, true, lockedVisible = false))
        assertEquals(TargetState.UNLOCKED, gate.state)
        assertTrue(gate.boostAllowed)
    }
}
