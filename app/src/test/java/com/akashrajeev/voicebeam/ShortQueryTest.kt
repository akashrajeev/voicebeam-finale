package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.engine.VoiceLearner
import org.junit.Assert.*
import org.junit.Test
class ShortQueryTest {
    @Test fun queryShortensWithoutChangingEnrollment() {
        val sizes=mutableListOf<Int>()
        val l=VoiceLearner({ sizes.add(it.size);floatArrayOf(1f,0f)},sampleRate=10,chunkSeconds=3f,querySeconds=2f,queryHopSeconds=.5f)
        l.beginEnrollment();repeat(3){l.feed(FloatArray(30){.1f},1f)}
        assertEquals(listOf(30,30,30),sizes);assertTrue(l.learned)
        l.feed(FloatArray(19){.1f},1f);assertEquals(3,sizes.size)
        l.feed(floatArrayOf(.1f),1f);assertEquals(20,sizes.last());assertEquals(20,l.scoreQuerySamples)
        l.feed(FloatArray(4){.1f},1f);assertEquals(4,sizes.size)
        l.feed(floatArrayOf(.1f),1f);assertEquals(5,sizes.size);assertEquals(25,l.scoreQuerySamples)
        assertEquals(20,l.querySamplesBuffered)
    }
}
