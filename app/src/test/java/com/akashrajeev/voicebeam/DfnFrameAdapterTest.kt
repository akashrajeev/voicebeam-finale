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

    private fun toneGain(hz:Double):Double {
        val a=DfnFrameAdapter{it};var t=0;var energy=0.0;var count=0
        repeat(120) { block ->
            val x=FloatArray(256){(.1*sin(2*PI*hz*t++/16000)).toFloat()}
            val y=a.process(x)
            if(block>20) { for(v in y){energy+=v*v;count++} }
        }
        return 20*log10(sqrt(energy/count)/(.1/sqrt(2.0)))
    }
    @Test fun consonantBandResponse() {
        assertEquals(0.0,toneGain(6000.0),.12)
        assertEquals(-.80,toneGain(7000.0),.25)
        assertEquals(0.0,toneGain(3000.0),.12)
    }
    @Test fun impulseDelayAndFiniteTail() {
        val a=DfnFrameAdapter{it};val y=ArrayList<Float>()
        repeat(20){block->y.addAll(a.process(FloatArray(256){k->if(block==0&&k==0).1f else 0f}).toList())}
        val peak=y.indices.maxByOrNull{abs(y[it])}!!
        assertTrue(peak in 286..288)
        assertTrue(y.all{it.isFinite()})
        assertTrue(y.takeLast(256).all{abs(it)<1e-6f})
    }
    @Test fun frameStreamNoPeriodicDiscontinuity() {
        val a=DfnFrameAdapter{it};var t=0;val output=ArrayList<Float>()
        repeat(60){output.addAll(a.process(FloatArray(256){(.1*sin(2*PI*1000*t++/16000)).toFloat()}).toList())}
        val stable=output.drop(1024)
        assertTrue(stable.zipWithNext().all{(x,y)->abs(y-x)<.045f})
    }
}
