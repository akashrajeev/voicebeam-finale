package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.*
import org.junit.Assert.*
import org.junit.Test

class ShadowListeningPolicyTest {
    @Test fun mixRecommendationsDoNotChangeCallerInputs() {
        val p = ShadowListeningPolicy()
        val actual = .7f
        assertEquals(1f, p.proposeMix(actual, TargetState.OTHER, true, .04f), 0f)
        assertEquals(1f, p.proposeMix(actual, TargetState.UNCERTAIN, false, .01f), 0f)
        assertEquals(.9f, p.proposeMix(actual, TargetState.TARGET, true, .04f), 0f)
        assertEquals(actual, p.proposeMix(actual, TargetState.OVERLAP, true, .04f), 0f)
        assertEquals(.7f, actual, 0f)
    }
    @Test fun invalidEnergyNeverRecommendsAnAdaptiveChange() {
        val p = ShadowListeningPolicy()
        for (r in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertEquals(.7f, p.proposeMix(.7f, TargetState.TARGET, true, r), 0f)
        }
    }
    @Test fun quietTargetPreservesActualMixAndInvalidSettingsHaveSafeProposal() {
        val p = ShadowListeningPolicy()
        assertEquals(.7f, p.proposeMix(.7f, TargetState.TARGET, true, 0f), 0f)
        assertEquals(1f, p.proposeMix(Float.NaN, TargetState.TARGET, true, .04f), 0f)
        assertEquals(0f, p.proposeMix(-1f, TargetState.UNCERTAIN, true, .04f), 0f)
        assertEquals(1f, p.proposeMix(2f, TargetState.UNCERTAIN, true, .04f), 0f)
    }
    @Test fun floorSamplesOnlyFiniteNonSpeechEnergy() {
        val p = ShadowListeningPolicy()
        assertNull(p.sampleFloor(true, .02f))
        assertNull(p.sampleFloor(false, Float.NaN))
        assertEquals(.01f, p.sampleFloor(false, .01f)!!, .00001f)
        assertEquals(.01f, p.sampleFloor(true, .8f)!!, .00001f)
        assertEquals(.011f, p.sampleFloor(false, .02f)!!, .00001f)
    }
    @Test fun newSessionPolicyStartsWithoutFloor() {
        val p = ShadowListeningPolicy(); p.sampleFloor(false, .01f)
        assertNull(ShadowListeningPolicy().sampleFloor(true, .01f))
    }
    @Test fun snapshotIsAValueNotAnOutputControl() {
        val t = ProofTelemetry(1000, TargetState.UNCERTAIN, 0f, null, .01f, .008f,
            .2f, null, null, .7f, .86f, 1f, .01f)
        val next = t.copy(rawRms = .02f)
        assertEquals(.01f, t.rawRms, 0f)
        assertEquals(.02f, next.rawRms, 0f)
    }
}
