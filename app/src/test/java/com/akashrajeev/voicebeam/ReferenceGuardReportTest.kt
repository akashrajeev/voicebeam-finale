package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.ReferenceGuardReport.Cause
import org.junit.Assert.*
import org.junit.Test

class ReferenceGuardReportTest {
    private fun r(sec: Float, cov: Float, cos: Float, failed: Boolean = false) = ReferenceGuardReport(sec, cov, cos, failed)

    @Test fun constantsMirrorExtractionSafety() {
        val c = ReferenceGuardReport.MIN_COVERAGE; val s = ReferenceGuardReport.MIN_SPLIT_COSINE
        assertTrue(ExtractionSafety.referenceTrusted(c, s))
        assertFalse(ExtractionSafety.referenceTrusted(c - 0.001f, s))
        assertFalse(ExtractionSafety.referenceTrusted(c, s - 0.001f))
    }
    @Test fun verdictAgreesWithExtractionSafetyOnLongEnoughWindows() {
        for (cov in listOf(0.3f, 0.5f, 0.9f)) for (cos in listOf(0.1f, 0.45f, 0.8f))
            assertEquals(ExtractionSafety.referenceTrusted(cov, cos), r(5f, cov, cos).trusted)
    }
    @Test fun tooShortWinsOverEverything() {
        assertEquals(Cause.TOO_SHORT, r(1.9f, 0.9f, 0.9f).cause)
        assertEquals(Cause.TOO_SHORT, r(1.9f, 0.1f, 0.1f, true).cause)
        assertEquals(Cause.OK, r(2.0f, 0.9f, 0.9f).cause)
    }
    @Test fun lowSpeechBeforeLowCosine() = assertEquals(Cause.LOW_SPEECH, r(5f, 0.3f, 0.1f).cause)
    @Test fun lowCosineIsMixedOrUnsteady() = assertEquals(Cause.MIXED_OR_UNSTEADY, r(3f, 0.59f, 0.32f).cause)
    @Test fun embedFailureNamedAsSuch() = assertEquals(Cause.EMBED_FAILED, r(5f, Float.NaN, Float.NaN, true).cause)
    @Test fun nanNeverPasses() {
        assertFalse(r(5f, Float.NaN, 0.9f).trusted)
        assertFalse(r(5f, 0.9f, Float.NaN).trusted)
    }
    @Test fun messageNamesNumbersAndHint() {
        val m = r(3f, 0.59f, 0.32f).userMessage()
        assertTrue(m, m.contains("3.0 s")); assertTrue(m, m.contains("0.32")); assertTrue(m, m.contains("0.45"))
        assertTrue(m, m.contains("5-8 seconds"))
        assertFalse(m, m.contains("\u2014"))
    }
    @Test fun lowSpeechMessageShowsPercent() {
        val m = r(5f, 0.31f, 0.7f).userMessage()
        assertTrue(m, m.contains("31%")); assertTrue(m, m.contains("50%"))
    }
    @Test fun tooShortMessageNamesLengthAndMinimum() {
        val m = r(1.5f, 0.9f, 0.9f).userMessage()
        assertTrue(m, m.contains("1.5 s")); assertTrue(m, m.contains("2 s"))
    }
    @Test fun diagLineIsOneLineWithAllThree() {
        val d = r(3f, 0.59f, 0.32f).diagLine()
        assertTrue(d, d.contains("3.0s")); assertTrue(d, d.contains("59%")); assertTrue(d, d.contains("0.32"))
        assertFalse(d.contains("\n"))
    }
    @Test fun unmeasuredShowsNotAvailable() {
        val d = r(5f, Float.NaN, Float.NaN, true).diagLine()
        assertTrue(d, d.contains("n/a"))
    }
}
