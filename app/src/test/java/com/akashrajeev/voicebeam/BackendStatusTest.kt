package com.akashrajeev.voicebeam
import org.junit.Assert.*
import org.junit.Test
import com.akashrajeev.voicebeam.core.BackendStatus
import com.akashrajeev.voicebeam.core.DiagnosticLog
class BackendStatusTest {
    @Test fun statusSurvivesEventRingRollover() {
        val status=BackendStatus();val log=DiagnosticLog(3)
        status.set("failed=asset_open error=FileNotFoundException")
        repeat(10){log.add("frame$it")}
        assertTrue(status.value.contains("FileNotFoundException"))
        assertEquals(3,log.snapshot().lines().size)
    }
    @Test fun boundedSingleLineAndClear() {
        val status=BackendStatus();status.set("failure\nreason\r"+"x".repeat(2000))
        assertEquals(1024,status.value.length);assertFalse(status.value.contains('\n'));assertFalse(status.value.contains('\r'))
        status.clear();assertEquals("not_started",status.value)
    }
}
