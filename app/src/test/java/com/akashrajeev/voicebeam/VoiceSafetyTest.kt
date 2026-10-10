package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.*
import com.akashrajeev.voicebeam.engine.VoiceLearner
import org.junit.Assert.*
import org.junit.Test
class VoiceSafetyTest {
    private fun input(seq: Long)=GateInputs(true,.002f,0f,1f,true,voiceScoreSequence=seq,voiceQuerySamples=seq*24000,voiceScoreAgeMs=0)
    @Test fun promotionNeedsTwoFreshComputationsNotRepeatedFrames() {
        val g=TargetGate();repeat(200){g.process(input(1))};assertEquals(TargetState.UNCERTAIN,g.state)
        g.process(input(2));assertEquals(TargetState.TARGET,g.state)
    }
    @Test fun freshVoicePromotesEvenWhenVadBlindButStaleDoesNot() {
        val g=TargetGate();g.process(input(1).copy(voiceActive=false));g.process(input(2).copy(voiceActive=false))
        assertEquals(TargetState.TARGET,g.state);assertTrue(g.boostAllowed)
        g.process(input(2).copy(voiceActive=false,voiceScoreAgeMs=1001));assertEquals(TargetState.UNCERTAIN,g.state)
    }
    @Test fun wearerOtherAndNearTalkerBlockOrResetPromotion() {
        for (conflict in listOf(input(2).copy(wearerVetoEnabled=true),input(2).copy(othersSpeaking=.4f),input(2).copy(voiceMatch=.1f))) {
            val g=TargetGate();g.process(input(1));g.process(conflict);g.process(input(3))
            assertEquals(TargetState.UNCERTAIN,g.state)
            g.process(input(4));assertEquals(TargetState.TARGET,g.state)
        }
    }
    @Test fun newAudioMustSpanOnePointFiveSeconds() {
        val g=TargetGate();g.process(input(1).copy(voiceQuerySamples=48000))
        g.process(input(2).copy(voiceQuerySamples=64000));assertEquals(TargetState.UNCERTAIN,g.state)
        g.process(input(3).copy(voiceQuerySamples=80000));assertEquals(TargetState.TARGET,g.state)
    }
    @Test fun noLockUnlearnedOrInvisibleCannotVoicePromote() {
        for (i in listOf(input(1).copy(hasLock=false),input(1).copy(voiceLearned=false),input(1).copy(lockedVisible=false))) {
            val g=TargetGate();g.process(i);g.process(i.copy(voiceScoreSequence=2));assertNotEquals(TargetState.TARGET,g.state)
        }
    }
    @Test fun explicitOtherRetainsRecallTwoPercentFloor() {
        val g=TargetGate().apply{quietOthers=1f};repeat(300){g.process(input(1).copy(voiceMatch=.1f))};assertEquals(.02f,g.gain,.001f)
    }
    private fun learner(embed: (FloatArray)->FloatArray?)=VoiceLearner(embed,sampleRate=10,chunkSeconds=1f)
    private val audio=FloatArray(10){.1f}
    @Test fun replacementKeepsOldProfileUntilThreeConsistentCaptures() {
        var next=floatArrayOf(1f,0f);val l=learner{next};l.beginEnrollment();repeat(3){l.feed(audio,1f)}
        val old=l.centroid!!;next=floatArrayOf(0f,1f);l.beginEnrollment();assertTrue(l.learned)
        repeat(2){l.feed(audio,1f);assertArrayEquals(old,l.centroid!!,0f)}
        l.feed(audio,1f);assertTrue(l.learned);assertArrayEquals(next,l.centroid!!,0f)
    }
    @Test fun twoInconsistentRoundsKeepOldAndAbort() {
        var next=floatArrayOf(1f,0f);val l=learner{next};l.beginEnrollment();repeat(3){l.feed(audio,1f)};val old=l.centroid!!
        l.beginEnrollment();repeat(2){next=floatArrayOf(1f,0f);l.feed(audio,1f);next=floatArrayOf(0f,1f);l.feed(audio,1f);l.feed(audio,1f)}
        assertFalse(l.enrollmentEnabled);assertTrue(l.learned);assertArrayEquals(old,l.centroid!!,0f)
    }
    @Test fun cancelledEnrollmentRetainsProfileExplicitResetForgets() {
        val l=learner{floatArrayOf(1f,0f)};l.beginEnrollment();repeat(3){l.feed(audio,1f)}
        l.beginEnrollment();l.cancelEnrollment();l.clearQuery();assertTrue(l.learned);l.reset();assertFalse(l.learned)
    }
    @Test fun timeoutKeepsPublishedProfile() {
        var now=0L;val l=VoiceLearner({floatArrayOf(1f,0f)},sampleRate=10,chunkSeconds=1f,clockMs={now})
        l.beginEnrollment();repeat(3){l.feed(audio,1f)};l.beginEnrollment();now=30001;l.feed(audio,1f)
        assertTrue(l.learned);assertFalse(l.enrollmentEnabled)
    }
}
