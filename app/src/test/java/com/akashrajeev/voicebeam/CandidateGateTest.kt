package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.*
import com.akashrajeev.voicebeam.engine.VoiceLearner
import org.junit.Assert.*
import org.junit.Test
class CandidateGateTest {
    private val voice = GateInputs(true, .22f, 0f, 1f, true)
    @Test fun strongVoiceUsesLowerLipThresholdButStillNeedsSpeech() {
        val g=TargetGate(); g.process(voice); assertEquals(TargetState.TARGET,g.state)
        g.process(voice.copy(lockedSpeaking=.1f)); assertEquals(TargetState.UNCERTAIN,g.state)
        g.process(voice.copy(voiceActive=false)); assertEquals(TargetState.UNCERTAIN,g.state)
        val cold=TargetGate();cold.process(voice.copy(voiceActive=false));assertFalse(cold.boostAllowed)
    }
    @Test fun holdCovers600msAndExpiresAt700ms() {
        val g=TargetGate(frameMs=10f);g.process(voice)
        val gap=voice.copy(voiceActive=false,lockedSpeaking=0f)
        repeat(60){g.process(gap)};assertTrue(g.boostAllowed)
        repeat(10){g.process(gap)};assertFalse(g.boostAllowed);assertEquals(0f,g.probability,0f)
    }
    @Test fun otherSpeechAndFaceLossCancelExtendedHold() {
        for(i in listOf(voice.copy(lockedVisible=false,voiceActive=false),voice.copy(lockedSpeaking=0f,othersSpeaking=.9f))) {
            val g=TargetGate();g.process(voice);g.process(i);assertFalse(g.boostAllowed)
        }
    }
    @Test fun provisionalBoostIsVisibleUnlearnedOnly() {
        val unlearned=voice.copy(voiceLearned=false)
        val g=TargetGate();g.process(unlearned);assertTrue(g.boostAllowed)
        assertEquals(TargetState.UNCERTAIN,g.state);assertEquals(1f,g.gain,.001f)
        for(i in listOf(unlearned.copy(lockedVisible=false),unlearned.copy(audioOnly=true),voice.copy(voiceMatch=.5f))) {
            val cold=TargetGate();cold.process(i);assertFalse(cold.boostAllowed)
        }
    }
    @Test fun preservedTemplateClearsQueryAndExplicitResetForgets() {
        val l=VoiceLearner({floatArrayOf(1f,0f)},1000,1f,3)
        l.beginEnrollment();l.feed(FloatArray(3000){.03f},1f)
        val template=l.centroid;assertTrue(l.learned)
        l.feed(FloatArray(1000){.03f},1f);assertNotNull(l.lastQueryEmbedding)
        l.clearQuery();assertTrue(l.learned);assertSame(template,l.centroid)
        assertEquals(0,l.querySamplesBuffered);assertNull(l.lastQueryEmbedding)
        l.reset();assertFalse(l.learned);assertNull(l.centroid)
    }
}
