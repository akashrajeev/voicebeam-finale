package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.HearingRumbleFilter
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
class HearingRumbleFilterTest {
    private fun db(hz: Double): Double {
        val f=HearingRumbleFilter();val x=FloatArray(32000){(.1*sin(2*PI*hz*it/16000)).toFloat()}
        f.process(x,x.size)
        val rms=sqrt(x.drop(16000).sumOf{it.toDouble()*it}/16000)
        return 20*log10(rms/(.1/sqrt(2.0)))
    }
    @Test fun lowRumbleReduced() { assertTrue(db(20.0)<-23.0) }
    @Test fun cutoffKnown() { assertEquals(-3.01,db(80.0),.08) }
    @Test fun speechBandPreserved() { assertTrue(abs(db(300.0))<.05);assertTrue(abs(db(1000.0))<.01) }
    @Test fun dcDecays() { val x=FloatArray(16000){.1f};HearingRumbleFilter().process(x,x.size);assertTrue(abs(x.last())<.00001f) }
    @Test fun finiteGuard() { val x=floatArrayOf(Float.NaN,Float.POSITIVE_INFINITY,.1f);HearingRumbleFilter().process(x,3);assertTrue(x.all{it.isFinite()}) }
    @Test fun resetIndependent() { val f=HearingRumbleFilter();val x=FloatArray(256){.1f};f.process(x,256);f.reset();val y=FloatArray(256){.1f};f.process(y,256);assertArrayEquals(x,y,0f) }
    @Test fun chunkContinuity() {
        val x=FloatArray(16000){(.1*sin(2*PI*123*it/16000)).toFloat()};val y=x.copyOf()
        HearingRumbleFilter().process(x,x.size);val f=HearingRumbleFilter()
        for (start in y.indices step 256) { val end=minOf(y.size,start+256);val chunk=y.copyOfRange(start,end);f.process(chunk,chunk.size);chunk.copyInto(y,start) }
        assertArrayEquals(x,y,0f)
    }
}
