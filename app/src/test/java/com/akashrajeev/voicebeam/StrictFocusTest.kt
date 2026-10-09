package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.*
import org.junit.Test
import org.junit.Assert.*
class StrictFocusTest {
    private val target=GateInputs(true,.22f,0f,1f,true)
    private val unknown=target.copy(voiceMatch=.5f,lockedSpeaking=0f)
    private fun gate()=TargetGate(10f).also { it.tuning=GateTuning(strictEnabled=true) }
    @Test fun strictUnknownSpeechSettlesToResidual() {
        val g=gate();repeat(250){g.process(unknown)}
        assertEquals(TargetState.UNCERTAIN,g.state);assertEquals(.2f,g.gain,.001f);assertFalse(g.boostAllowed)
    }
    @Test fun boundedHangoverProtectsFlickeringMatchThenFallsSlowly() {
        val g=gate();repeat(100){g.process(target)}
        repeat(60){g.process(unknown)};assertTrue(g.gain>.99f)
        repeat(10){g.process(unknown)};assertTrue(g.gain>.9f)
        repeat(200){g.process(unknown)};assertEquals(.2f,g.gain,.001f)
        repeat(10){g.process(target)};assertTrue(g.gain>.94f)
    }
    @Test fun noSpeechAndOverlapStillPassButLostFaceSpeechSuppresses() {
        for(i in listOf(unknown.copy(voiceActive=false),target.copy(othersSpeaking=.9f,lockedSpeaking=.9f))) {
            val g=gate();repeat(200){g.process(i)};assertTrue(g.gain>.99f)
        }
        val g=gate();g.process(target);repeat(200){g.process(unknown.copy(lockedVisible=false))}
        assertEquals(.2f,g.gain,.001f);assertFalse(g.boostAllowed)
    }
    @Test fun runtimeThresholdAndResidualChangeWithoutNewGate() {
        val g=gate();g.tuning=GateTuning(true,.4f,0f,.6f).sanitized()
        g.process(target.copy(voiceMatch=.7f));assertEquals(TargetState.TARGET,g.state)
        repeat(200){g.process(unknown)};assertEquals(.4f,g.gain,.001f)
        g.tuning=GateTuning(strictEnabled=false);repeat(30){g.process(unknown)};assertTrue(g.gain>.99f)
    }
    @Test fun configRejectsInvalidCalibrationAndSanitizesNonfiniteNumbers() {
        val t=GateTuning(true,Float.NaN,Float.POSITIVE_INFINITY,-2f).sanitized()
        assertEquals(.2f,t.residualGain,0f);assertEquals(700f,t.hangoverMs,0f);assertEquals(.2f,t.targetThreshold,0f)
        assertEquals(.5f,SpeakerProfile("models/test.onnx",.1f,.9f).score(.5f),.0001f)
        try { SpeakerProfile("models/test.onnx",.9f,.1f);fail() } catch(_:IllegalArgumentException) {}
    }
}
