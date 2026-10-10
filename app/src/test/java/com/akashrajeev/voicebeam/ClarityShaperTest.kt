package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.ClarityShaper
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*
class ClarityShaperTest {
    private fun tone(hz: Double, n: Int) = FloatArray(n) { (.1 * sin(2 * PI * hz * it / 16000)).toFloat() }
    private fun rms(a: FloatArray, from: Int) = sqrt(a.drop(from).map { it.toDouble() * it }.average())
    private fun gainDb(hz: Double, gate: Float, allowed: Boolean): Double {
        val s = ClarityShaper(); val x = tone(hz, 16000); val y = x.copyOf()
        for (i in 0 until 16000 step 256) { val b = y.copyOfRange(i, i + 256); s.process(b, 256, gate, allowed); b.copyInto(y, i) }
        return 20 * log10(rms(y, 4000) / rms(x, 4000))
    }
    @Test fun liftsPresenceWhenOpen() { assertEquals(3.5, gainDb(2800.0, 1f, true), .4) }
    @Test fun leavesLowAndVeryHighBandsNearUnity() {
        assertEquals(0.0, gainDb(200.0, 1f, true), .5); assertEquals(0.0, gainDb(7500.0, 1f, true), 1.0) }
    @Test fun noExtraGainWhenGateAttenuatesOrBoostBlocked() {
        assertEquals(0.0, gainDb(2800.0, .2f, true), .01)
        assertEquals(0.0, gainDb(2800.0, .89f, true), .01)
        assertEquals(0.0, gainDb(2800.0, 1f, false), .01)
    }
    @Test fun nonFiniteInputIsSafeAndSilent() {
        val s = ClarityShaper(); val b = FloatArray(64) { if (it == 3) Float.NaN else .1f }
        s.process(b, 64, 1f, true); assertTrue(b.all { it.isFinite() })
    }
    @Test fun peakGrowthIsBoundedAndLimiterStillHolds() {
        val s = ClarityShaper(); val b = tone(2800.0, 512).map { it * 8f }.toFloatArray(); s.process(b, 512, 1f, true)
        assertTrue(b.maxOf { abs(it) } < .8 * 1.6)
    }
}
