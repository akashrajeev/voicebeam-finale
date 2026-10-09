package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.*
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.abs
class ListenEnvelopeTest {
    @Test fun peakLimiterAndSmoothGate() {
        val e=ListenEnvelope();val raw=FloatArray(256){.8f};val g=FloatArray(256);val o=FloatArray(256)
        e.process(raw,256,.04f,4f,g,o)
        assertTrue(o.all{abs(it)<=.95001f});assertTrue(g[0]>.7f);assertEquals(.032f,g.last(),.0001f)
        val last=g.last();e.process(raw,256,1f,1f,g,o);assertTrue(abs(g[0]-last)<.004f)
    }
    @Test fun fullAttenuationFloorAndTargetRecovery() {
        val gate=TargetGate(frameMs=16f);gate.quietOthers=1f
        repeat(150){gate.process(GateInputs(true,0f,.9f,.05f,true))}
        assertEquals(.02f,gate.gain,.0001f)
        // Hysteresis needs 3 frames to transition OTHER->TARGET; prime with 3 prep frames
        repeat(3){gate.process(GateInputs(true,.9f,0f,.95f,true))}
        var missed=0;repeat(60){if(gate.process(GateInputs(true,.9f,0f,.95f,true))<.1f)missed++}
        assertEquals(0,missed);assertTrue(gate.gain>.94f)
    }
}
