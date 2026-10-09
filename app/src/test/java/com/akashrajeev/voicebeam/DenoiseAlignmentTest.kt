package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.DenoiseAlignment
import org.junit.Assert.*
import org.junit.Test

class DenoiseAlignmentTest {
    @Test fun warmupDoesNotMixNextFrame() {
        val a = DenoiseAlignment(8); val out = FloatArray(2)
        a.push(floatArrayOf(1f,2f)); a.mix(floatArrayOf(),.5f,out,0)
        a.push(floatArrayOf(3f,4f)); a.mix(floatArrayOf(.5f,1f),.5f,out,2)
        assertArrayEquals(floatArrayOf(.75f,1.5f),out,0f)
        a.push(floatArrayOf(5f,6f)); a.mix(floatArrayOf(1.5f,2f),0f,out,2)
        assertArrayEquals(floatArrayOf(3f,4f),out,0f)
    }
    @Test fun ringWrapAndReset() {
        val a=DenoiseAlignment(4);val out=FloatArray(2)
        repeat(10) { a.push(floatArrayOf(it.toFloat(),-it.toFloat()));a.mix(floatArrayOf(99f,99f),0f,out,2);assertEquals(it.toFloat(),out[0],0f) }
        a.push(floatArrayOf(8f,9f));a.reset();a.push(floatArrayOf(4f,5f));a.mix(floatArrayOf(1f,2f),1f,out,2)
        assertArrayEquals(floatArrayOf(1f,2f),out,0f)
    }
}
