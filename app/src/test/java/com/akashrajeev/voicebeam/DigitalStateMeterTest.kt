package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.*
import org.junit.Assert.*
import org.junit.Test

class DigitalStateMeterTest {
    private fun t(ms:Long, gate:TargetState=TargetState.TARGET, raw:Float=.1f, out:Float=.05f) =
        ProofTelemetry(ms,gate,1f,null,raw,out,0f,null,null,.7f,.86f,.7f,null)
    private fun reading(s:DigitalMeterSnapshot,g:TargetState=TargetState.TARGET)=s.readings.first{it.gate==g}
    @Test fun minimumSamplesAndKnownRatio() {
        val m=DigitalStateMeter();assertNull(reading(m.accept(t(0))).ratioDb)
        assertNull(reading(m.accept(t(1000))).ratioDb)
        assertEquals(-6.0206,reading(m.accept(t(2000))).ratioDb!!,.0001)
    }
    @Test fun statesNeverPoolAndOverlapNotAttributed() {
        val m=DigitalStateMeter();m.accept(t(0));m.accept(t(1000,TargetState.OTHER));m.accept(t(2000,TargetState.OVERLAP))
        val r=m.accept(t(3000,TargetState.UNCERTAIN));assertEquals(1,reading(r).count);assertEquals(1,reading(r,TargetState.OTHER).count)
        assertEquals(1,reading(r,TargetState.UNCERTAIN).count);assertTrue(r.readings.all{it.ratioDb==null})
    }
    @Test fun duplicateAndHighRateSamplesDoNotInflateCount() {
        val m=DigitalStateMeter();m.accept(t(0));m.accept(t(0));m.accept(t(500));assertEquals(2,reading(m.accept(t(1000))).count)
    }
    @Test fun badRawOrOutputRejectedAndZeroOutputNotFabricated() {
        val m=DigitalStateMeter();m.accept(t(0,raw=0f));m.accept(t(1000,raw=Float.NaN));m.accept(t(2000,out=Float.POSITIVE_INFINITY))
        assertEquals(0,reading(m.accept(t(3000,out=-1f))).count)
        m.accept(t(4000,out=0f));m.accept(t(5000,out=0f));assertNull(reading(m.accept(t(6000,out=0f))).ratioDb)
    }
    @Test fun rollingWindowExpiryAndReset() {
        val m=DigitalStateMeter();m.accept(t(0));m.accept(t(1000));m.accept(t(2000))
        assertEquals(1,reading(m.accept(t(18000))).count);m.reset();assertEquals(1,reading(m.accept(t(19000))).count)
    }
    @Test fun energyRatioNotAverageDecibels() {
        val m=DigitalStateMeter();m.accept(t(0,raw=.1f,out=.1f));m.accept(t(1000,raw=.2f,out=.1f))
        val db=reading(m.accept(t(2000,raw=.3f,out=.1f))).ratioDb!!
        assertEquals(10*kotlin.math.log10(.03/.14),db,.00001)
    }
    @Test fun reversedClockClearsPriorSession() {
        val m=DigitalStateMeter();m.accept(t(10000));assertEquals(1,reading(m.accept(t(0))).count)
    }
}
