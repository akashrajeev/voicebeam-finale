package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import java.util.Random

class SpatialFocusTest {
    private fun signal(lag: Int, duplicates: Boolean = false): FloatArray {
        val r = Random(8); val source = FloatArray(1700) { (r.nextFloat()-.5f)*.15f }
        return FloatArray(3072) { k ->
            val t = k/2+30
            source[if (k%2 == 0 || duplicates) t else t-lag]
        }
    }
    private fun inputs() = GateInputs(true, .9f, 0f, .95f, true,
        lockedFaceId=1, targetX=.3f, targetY=.5f, visibleFaceCount=2,
        visionAgeMs=10, voiceAgeMs=20, voiceLearned=true)
    private fun trained(f: SpatialFocus): Long {
        var now=0L
        repeat(40) { f.update(SpatialCue(3f, .9f, .3f, 0f, true), inputs(), now, 16f); now+=16 }
        return now
    }
    @Test fun timingDetectsBothDirections() {
        for (lag in listOf(-3,3,8)) {
            val c=StereoTiming.estimate(signal(lag))
            assertTrue(c.toString(), c.usable); assertEquals(lag.toFloat(),c.lagSamples,.1f)
        }
    }
    @Test fun duplicateQuietInvalidAndIncoherentChannelsDisableCue() {
        assertFalse(StereoTiming.estimate(signal(0,true)).usable)
        assertFalse(StereoTiming.estimate(FloatArray(3072)).usable)
        val bad=signal(3); bad[2]=Float.NaN; assertFalse(StereoTiming.estimate(bad).usable)
        assertFalse(StereoTiming.estimate(signal(3),16000).usable)
        val r=Random(42); assertFalse(StereoTiming.estimate(FloatArray(3072){r.nextFloat()-.5f}).usable)
    }
    @Test fun calibrationRequiresFreshIdentityConfirmedSoloFace() {
        for (i in listOf(inputs().copy(voiceMatch=.6f),inputs().copy(othersSpeaking=.8f),
            inputs().copy(voiceAgeMs=900),inputs().copy(visionAgeMs=500),
            inputs().copy(audioOnly=true),inputs().copy(voiceLearned=false))) {
            val f=SpatialFocus()
            repeat(100) { assertNull(f.update(SpatialCue(3f,.9f,.3f,0f,true),i,it*16L,16f)) }
        }
    }
    @Test fun calibratedAgreementAndDisagreement() {
        val f=SpatialFocus(); val now=trained(f)
        val i=inputs().copy(voiceMatch=.75f, lockedSpeaking=.4f)
        assertEquals(1f,f.update(SpatialCue(3f,.9f,.3f,0f,true),i,now,16f)!!,0f)
        assertEquals(-1f,f.update(SpatialCue(-3f,.9f,.3f,0f,true),i,now+16,16f)!!,0f)
        assertNull(f.update(SpatialCue(5f,.9f,.3f,0f,true),i,now+32,16f))
    }
    @Test fun lockMovementVisibilityExpiryAndOverlapInvalidateVote() {
        for (i in listOf(inputs().copy(lockedFaceId=2),inputs().copy(targetX=.6f),
            inputs().copy(targetY=.8f),inputs().copy(lockedVisible=false),
            inputs().copy(voiceAgeMs=900),inputs().copy(othersSpeaking=.8f))) {
            val f=SpatialFocus(); val now=trained(f)
            assertNull(f.update(SpatialCue(3f,.9f,.3f,0f,true),i,now,16f))
        }
        val f=SpatialFocus(); val now=trained(f)
        assertNull(f.update(SpatialCue(3f,.9f,.3f,0f,true),inputs(),now+11000,16f))
    }
    @Test fun tiebreakerResolvesOnlyBorderlineNeverOverlapOrStrongMismatch() {
        val g=TargetGate(); val i=inputs().copy(lockedSpeaking=.4f,voiceMatch=.75f,spatialAgreement=1f)
        assertEquals(0f,g.targetProbability(i.copy(spatialAgreement=null)),0f)
        assertEquals(.95f,g.targetProbability(i),0f)
        for (j in listOf(i.copy(othersSpeaking=.8f,lockedSpeaking=.9f),
            i.copy(voiceMatch=.1f),i.copy(lockedVisible=false),i.copy(voiceAgeMs=900),
            i.copy(visibleFaceCount=1),i.copy(voiceLearned=false))) {
            g.targetProbability(j); assertNotEquals(TargetState.TARGET,g.state)
        }
        g.targetProbability(inputs().copy(voiceMatch=.45f,lockedSpeaking=.1f,spatialAgreement=-1f))
        assertEquals(TargetState.OTHER,g.state)
    }
    @Test fun customThresholdRemainsMeaningful() {
        val g=TargetGate(); g.tuning=GateTuning(targetThreshold=.95f)
        g.targetProbability(inputs().copy(lockedSpeaking=.4f,voiceMatch=.75f,spatialAgreement=1f))
        assertEquals(TargetState.UNCERTAIN,g.state)
    }
    @Test fun interruptedCalibrationDoesNotAccumulateOldFragments() {
        val f=SpatialFocus(); val c=SpatialCue(3f,.9f,.3f,0f,true)
        repeat(30){f.update(c,inputs(),it*16L,16f)}
        assertNull(f.update(SpatialCue(),inputs(),480,16f))
        assertNull(f.update(c,inputs().copy(voiceMatch=.75f,lockedSpeaking=.4f),496,16f))
    }
    @Test fun absentSpatialCuePreservesBaseline() {
        val g=TargetGate()
        assertEquals(.95f,g.targetProbability(inputs()),0f)
        assertEquals(.5f,g.targetProbability(inputs().copy(othersSpeaking=.9f)),0f)
    }
    @Test fun decimatorPassesVoiceAndRejectsAliasedUltrasound() {
        fun amplitude(hz:Double):Double {
            val d=StereoDownsample(); val mono=FloatArray(512)
            repeat(10) { block ->
                val s=FloatArray(3072) { k -> sin(2*PI*hz*(block*1536+k/2)/48000).toFloat() }
                d.process(s,mono)
            }
            return sqrt(mono.map{it*it.toDouble()}.average())
        }
        assertTrue(amplitude(1000.0)>.6)
        assertTrue(amplitude(12000.0)<.01)
    }
    @Test fun soloPolicyRequiresAllIndependentSignalsAndHonorsOff() {
        val i=inputs().copy(visibleFaceCount=1)
        assertTrue(SoloTargetPolicy.confirmed(i,1f))
        for (j in listOf(i.copy(visibleFaceCount=2),i.copy(voiceMatch=.7f),
            i.copy(voiceAgeMs=900),i.copy(visionAgeMs=500),i.copy(lockedVisible=false),
            i.copy(voiceActive=false),i.copy(lockedSpeaking=.2f),i.copy(audioOnly=true)))
            assertFalse(SoloTargetPolicy.confirmed(j,1f))
        assertFalse(SoloTargetPolicy.confirmed(i,null))
        assertFalse(SoloTargetPolicy.confirmed(i,-1f))
        assertEquals(1f,SoloTargetPolicy.mix(.7f,true),0f)
        assertEquals(0f,SoloTargetPolicy.mix(0f,true),0f)
        assertEquals(.7f,SoloTargetPolicy.mix(.7f,false),0f)
    }
    @Test fun resamplerPreservesBlockBoundaries() {
        val input=signal(3); val a=FloatArray(512); StereoDownsample().process(input,a)
        val d=StereoDownsample(); val b=FloatArray(256); val c=FloatArray(256)
        d.process(input.copyOfRange(0,1536),b);d.process(input.copyOfRange(1536,3072),c)
        assertArrayEquals(a,b+c,0f)
    }
}
