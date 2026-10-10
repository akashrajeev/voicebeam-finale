package com.akashrajeev.voicebeam
import org.junit.Test
import org.junit.Assert.*
import com.akashrajeev.voicebeam.core.ExtractionSafety
class ExtractionSafetyTest {
    @Test fun weakReferenceFailsClosed() {
        assertFalse(ExtractionSafety.referenceTrusted(.8f,.3f))
        assertFalse(ExtractionSafety.referenceTrusted(.1f,.9f))
        assertFalse(ExtractionSafety.referenceTrusted(Float.NaN,.9f))
        assertTrue(ExtractionSafety.referenceTrusted(.8f,.6f))
    }
    @Test fun pausesStayClosedAndNoAmplification() {
        val x=FloatArray(512*20){.006f};val y=FloatArray(x.size){.040f}
        val silent=ExtractionSafety.protect(x,y,BooleanArray(20))
        assertTrue(silent.all{it==0f})
        val voiced=ExtractionSafety.protect(x,y,BooleanArray(20){true})
        assertTrue(voiced.all{kotlin.math.abs(it)<=.00601f})
    }
    @Test fun speechKeepsContextButNotLongPauses() {
        val x=FloatArray(512*20){.1f};val speech=BooleanArray(20){it in 5..8}
        val y=ExtractionSafety.protect(x,x,speech)
        assertEquals(.1f,y[6*512],.00001f);assertEquals(0f,y[18*512],.00001f)
    }
}
