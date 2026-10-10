package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.*
import org.junit.Assert.*
import org.junit.Test

class HearingMixTest {
    private val target = GateInputs(true,.8f,0f,.9f,true)
    @Test fun targetDryBypassHalved() {
        assertEquals(.85f,HearingMix().target(.7f,target,TargetState.TARGET),.0001f)
    }
    @Test fun uncertaintyKeepsSpeechWithoutVolumeGate() {
        assertEquals(.95f,HearingMix().target(.7f,target,TargetState.UNCERTAIN),.0001f)
    }
    @Test fun overlapHasNoRawBypass() {
        assertEquals(1f,HearingMix().target(.7f,target,TargetState.OVERLAP),0f)
    }
    @Test fun otherHasNoRawBypass() {
        assertEquals(1f,HearingMix().target(.7f,target,TargetState.OTHER),0f)
    }
    @Test fun missingFaceUsesFullWetAndNoBoost() {
        val i=target.copy(lockedVisible=false)
        val gate=TargetGate();gate.process(i)
        assertFalse(gate.boostAllowed)
        assertEquals(TargetState.UNCERTAIN,gate.state)
        assertEquals(1f,HearingMix().target(.7f,i,gate.state),0f)
    }
    @Test fun userFullWetNotReduced() {
        assertEquals(1f,HearingMix().target(1f,target,TargetState.TARGET),0f)
    }
    @Test fun userOffHonored() {
        assertEquals(0f,HearingMix().target(0f,target,TargetState.OTHER),0f)
    }
    @Test fun unlockedKeepsUserMix() {
        assertEquals(.7f,HearingMix().target(.7f,target.copy(hasLock=false),TargetState.UNLOCKED),0f)
    }
    @Test fun unlearnedPreservationFloor() {
        assertEquals(.85f,HearingMix().target(.7f,target.copy(voiceLearned=false),TargetState.UNCERTAIN),0f)
    }
    @Test fun audioOnlyDoesNotRequireFace() {
        assertEquals(.85f,HearingMix().target(.7f,target.copy(audioOnly=true,lockedVisible=false),TargetState.TARGET),0f)
    }
    @Test fun invalidSettingSafeAndFinite() {
        assertEquals(.85f,HearingMix().target(Float.NaN,target,TargetState.TARGET),0f)
    }
    @Test fun transitionBoundedMonotonic() {
        val h=HearingMix();h.next(.7f,target,TargetState.TARGET,256)
        val first=h.next(.7f,target,TargetState.OTHER,256)
        assertTrue(first>.85f&&first<1f)
        var prior=first
        repeat(40) { val now=h.next(.7f,target,TargetState.OTHER,256);assertTrue(now>=prior&&now<=1f);prior=now }
        assertTrue(prior>.999f)
    }
    @Test fun knownNoiseBypassReductionNotExtraction() {
        val a=DenoiseAlignment();a.push(floatArrayOf(1f,1f))
        val out=FloatArray(2);a.mix(floatArrayOf(0f,0f),.85f,out,2)
        assertEquals(.15f,out[0],.0001f)
        assertEquals(.15f,out[1],.0001f)
    }
    @Test fun retainedInterfererStillPasses() {
        val a=DenoiseAlignment();a.push(floatArrayOf(1f));val out=FloatArray(1)
        a.mix(floatArrayOf(1f),1f,out,1);assertEquals(1f,out[0],0f)
    }
    @Test fun fallbackFirstThenRateLimited() {
        val f=FallbackCounter();assertTrue(f.record(10));assertFalse(f.record(11));assertTrue(f.record(5010));assertEquals(3L,f.count)
    }
    @Test fun fallbackClockResetHandled() {
        val f=FallbackCounter();assertTrue(f.record(100));assertTrue(f.record(50))
    }
    @Test fun invalidDenoiserFramesRejected() {
        assertFalse(DenoiseOutput.valid(floatArrayOf(Float.NaN),1))
        assertFalse(DenoiseOutput.valid(floatArrayOf(Float.POSITIVE_INFINITY),1))
        assertFalse(DenoiseOutput.valid(floatArrayOf(1f),2))
        assertFalse(DenoiseOutput.valid(floatArrayOf(1f,1f),1))
        assertTrue(DenoiseOutput.valid(floatArrayOf(1f),1))
        assertTrue(DenoiseOutput.valid(floatArrayOf(),256))
    }
}
