package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.ExtractionRate
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
class ExtractionRateTest {
    @Test fun dimensionsFiniteAndDc() {
        val x=FloatArray(16000){.2f};val y=ExtractionRate.down(x)
        assertEquals(8000,y.size);assertTrue(y.all{it.isFinite()})
        assertEquals(.2,y.sliceArray(100..7900).average(),.001)
        val z=ExtractionRate.up(y,16000);assertEquals(16000,z.size);assertTrue(z.all{it.isFinite()})
        assertEquals(.2,z.sliceArray(100..15900).average(),.001)
    }
    @Test fun antiAliasAndRoundTrip() {
        val low=FloatArray(16000){sin(2*PI*1000*it/16000).toFloat()*.1f}
        val high=FloatArray(16000){sin(2*PI*6000*it/16000).toFloat()*.1f}
        fun rms(a:FloatArray)=sqrt(a.sliceArray(100 until a.size-100).sumOf{it.toDouble()*it}/(a.size-200))
        assertTrue(rms(ExtractionRate.down(high))<.003)
        assertTrue(rms(ExtractionRate.up(ExtractionRate.down(low),low.size))>.065)
    }
    @Test fun rejectsBadReference() {
        assertFalse(ExtractionRate.valid(floatArrayOf(Float.NaN)))
        assertFalse(ExtractionRate.valid(FloatArray(100)))
        assertTrue(ExtractionRate.valid(FloatArray(100){.01f}))
    }
}
