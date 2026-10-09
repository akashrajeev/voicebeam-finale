package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.DiagnosticLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticLogTest {
    @Test fun boundedLogDropsOldest() {
        val log = DiagnosticLog(2)
        log.add("old"); log.add("new"); log.add("latest")
        assertEquals("new\nlatest", log.snapshot())
        assertFalse(log.snapshot().contains("old"))
    }
    @Test fun clearRemovesEverything() {
        val log = DiagnosticLog(); log.add("route=unknown"); log.clear()
        assertEquals("", log.snapshot())
    }
    @Test fun oversizedLineIsBounded() {
        val log = DiagnosticLog(); log.add("x".repeat(3000))
        assertEquals(2048, log.snapshot().length)
    }
}
