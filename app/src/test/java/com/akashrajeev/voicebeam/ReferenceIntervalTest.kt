package com.akashrajeev.voicebeam
import org.junit.Test
import org.junit.Assert.*
import com.akashrajeev.voicebeam.core.ReferenceInterval
class ReferenceIntervalTest {
    private fun tone()=FloatArray(16_000*12){.1f}
    @Test fun exactSeconds() { assertEquals(48000,ReferenceInterval.slice(tone(),2.0,5.0).size) }
    @Test fun tenSeconds() { assertEquals(160000,ReferenceInterval.slice(tone(),0.0,10.0).size) }
    @Test fun rejectsBadIntervals() {
        for((a,b) in listOf(-1.0 to 2.0,0.0 to .9,0.0 to 10.1,11.0 to 13.0,Double.NaN to 3.0,0.0 to Double.POSITIVE_INFINITY,5.0 to 3.0)) {
            try { ReferenceInterval.slice(tone(),a,b);fail("Accepted $a..$b") } catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun silenceAndNonfiniteRejected() {
        for(s in listOf(FloatArray(16000*3),FloatArray(16000*3){Float.NaN})) {
            try { ReferenceInterval.slice(s,0.0,3.0);fail("Accepted invalid audio") } catch(_:IllegalArgumentException) {}
        }
    }
}
