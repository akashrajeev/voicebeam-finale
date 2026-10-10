package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.*
import org.junit.Assert.*
import org.junit.Test
class ConversationGateTest {
    private fun input(vm: Float?=.95f)=GateInputs(true,.01f,0f,vm,true,voiceScoreSequence=1,voiceScoreAgeMs=0,voiceQuerySamples=48000)
    @Test fun singleFreshQueryPromotesWithoutLips() {
        val g=TargetGate().apply{tuning=GateTuning(conversationCandidate=true)};g.process(input().copy(voiceActive=false));assertEquals(TargetState.TARGET,g.state);assertTrue(g.boostAllowed)
    }
    @Test fun freshNegativeSuppressesUncertainButNotActiveTargetLips() {
        val g=TargetGate().apply{tuning=GateTuning(conversationCandidate=true)};repeat(200){g.process(input(.3f))};assertEquals(TargetState.OTHER,g.state);assertTrue(g.gain<.3f)
        g.process(input(.3f).copy(lockedSpeaking=.6f));assertEquals(TargetState.UNCERTAIN,g.state)
    }
    @Test fun staleUnknownAndOverlapPassWithoutBoostEvenFullStrict() {
        for(i in listOf(input().copy(voiceScoreAgeMs=1001),input(null),input().copy(lockedSpeaking=.7f,othersSpeaking=.7f))) {
            val g=TargetGate().apply{tuning=GateTuning(strictEnabled=true,strictFull=true,conversationCandidate=true)}
            repeat(300){g.process(i)};assertEquals(1f,g.gain,.001f);assertFalse(g.boostAllowed)
        }
    }
    @Test fun nearTalkerAndWearerVetoNeverPromote() {
        for(i in listOf(input().copy(othersSpeaking=.4f),input().copy(wearerVetoEnabled=true))) {
            val g=TargetGate().apply{tuning=GateTuning(conversationCandidate=true)};g.process(i);assertNotEquals(TargetState.TARGET,g.state)
        }
    }
}
