package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.*
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*
class EnhPolicyBenchTest {
    @Test fun matchedScriptedTurnsPreserveTargetAndSuppressKnownOther() {
        val arms=listOf(
            "target" to GateInputs(true,.9f,0f,.95f,true),
            "other" to GateInputs(true,0f,.9f,.05f,true),
            "overlap" to GateInputs(true,.9f,.9f,.5f,true),
            "ambiguous" to GateInputs(true,0f,0f,.5f,true),
            "conflict" to GateInputs(true,.9f,0f,.05f,true),
            "incomplete" to GateInputs(true,.9f,0f,.05f,true,voiceLearned=false))
        for((name,i) in arms){
            val gate=TargetGate(frameMs=10f);var falseMute=0;var boost=0
            repeat(400){gate.process(i);if(gate.gain<.1f)falseMute++;if(gate.boostAllowed)boost++}
            println("ENH_POLICY scripted=$name gain=${gate.gain} attenuationDb=${20*log10(gate.gain)} below10percent=$falseMute/400 boosted=$boost/400")
            if(name=="other")assertTrue(gate.gain<=.151f) else assertEquals(0,falseMute)
            if(name=="target" || name=="incomplete")assertTrue(gate.boostAllowed) else assertFalse(gate.boostAllowed)
        }
    }
}
