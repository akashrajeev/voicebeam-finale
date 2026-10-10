package com.akashrajeev.voicebeam
import org.junit.Assert.*
import org.junit.Test
import com.akashrajeev.voicebeam.core.HearingRuntimeStats
class HearingRuntimeStatsTest {
    @Test fun allBatchP95BoundAndDeadline() {
        val s=HearingRuntimeStats();repeat(95){s.batch(3000)};repeat(4){s.batch(5000)};s.batch(16000)
        assertEquals(100L,s.batches);assertEquals(3500L,s.p95UpperUs());assertEquals(16000L,s.maxUs);assertEquals(1L,s.deadlineMisses)
    }
    @Test fun overflowBoundedAndEmpty() {
        val s=HearingRuntimeStats();assertEquals(0L,s.p95UpperUs());s.batch(1000000)
        assertEquals(1000000L,s.p95UpperUs());assertEquals(1L,s.deadlineMisses)
    }
    @Test fun shortWritesCountMissingSamplesNotErrorCodes() {
        val s=HearingRuntimeStats();s.write(256,256);s.write(0,256);s.write(128,256);s.write(-3,256)
        assertEquals(2L,s.shortWrites);assertEquals(384L,s.missingWriteSamples)
    }
}
