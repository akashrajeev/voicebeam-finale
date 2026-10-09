package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.DfnFrameAdapter
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
class DfnFrameAdapterTest {
    @Test fun framingBoundedAndFinite() {
        var calls=0
        val a=DfnFrameAdapter { x -> assertEquals(480,x.size);calls++;x }
        repeat(100) { val out=a.process(FloatArray(256){.1f});assertEquals(256,out.size);assertTrue(out.all{it.isFinite()}) }
        assertEquals(160,calls)
    }
    @Test fun initialBufferSilence() { val a=DfnFrameAdapter{it};assertTrue(a.process(FloatArray(256){.1f}).all{it==0f}) }
    @Test fun constantSteadyPreserved() { val a=DfnFrameAdapter{it};var y=FloatArray(256);repeat(20){y=a.process(FloatArray(256){.1f})};assertTrue(y.all{abs(it-.1f)<.002f}) }
    @Test fun nonfiniteOutputRejected() {
        val a=DfnFrameAdapter{FloatArray(480){Float.NaN}}
        try { a.process(FloatArray(256));fail("must reject") } catch (_:IllegalArgumentException) {}
    }
    @Test fun wrongLengthRejected() {
        val a=DfnFrameAdapter{FloatArray(1)}
        try { a.process(FloatArray(256));fail("must reject") } catch (_:IllegalArgumentException) {}
    }
    @Test fun amplitudeFiniteForFullScale() { val a=DfnFrameAdapter{it};repeat(20){val y=a.process(FloatArray(256){k->if(k%2==0)1f else -1f});assertTrue(y.all{it.isFinite()&&abs(it)<1.2f})} }
}
