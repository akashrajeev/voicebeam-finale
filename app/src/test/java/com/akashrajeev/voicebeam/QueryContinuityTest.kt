package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.engine.VoiceLearner
import com.akashrajeev.voicebeam.core.DiagnosticLog
import org.junit.Assert.*
import org.junit.Test

class QueryContinuityTest {
    @Test fun rollingThreeSecondsScoresEverySecondAndPreservesTail() {
        val windows = mutableListOf<FloatArray>()
        val l = VoiceLearner({ windows.add(it); floatArrayOf(1f, 0f) }, 4, 3f, 1)
        l.beginEnrollment(); l.feed(FloatArray(12) { .03f }, 0f); windows.clear()
        assertNull(l.feed(FloatArray(11) { it.toFloat() }, 1f))
        assertNotNull(l.feed(floatArrayOf(11f), 1f))
        assertNull(l.feed(floatArrayOf(12f, 13f, 14f), 1f))
        assertNotNull(l.feed(floatArrayOf(15f), 1f))
        assertArrayEquals(FloatArray(12) { it.toFloat() }, windows[0], 0f)
        assertArrayEquals(FloatArray(12) { it + 4f }, windows[1], 0f)
        assertEquals(12, l.querySamplesBuffered)
    }
    @Test fun oversizedQueryScoresAllHopsWithoutDiscardingTail() {
        val windows = mutableListOf<FloatArray>()
        val l = VoiceLearner({ windows.add(it); floatArrayOf(1f, 0f) }, 4, 3f, 1)
        l.beginEnrollment(); l.feed(FloatArray(12) { .03f }, 0f); windows.clear()
        l.feed(FloatArray(22) { it.toFloat() }, 1f)
        assertEquals(3, windows.size)
        l.feed(floatArrayOf(22f, 23f), 1f)
        assertEquals(4, windows.size)
        assertArrayEquals(FloatArray(12) { it + 12f }, windows.last(), 0f)
    }
    @Test fun queryUpdatesNeverAdaptFrozenEnrollment() {
        var query = false
        val l = VoiceLearner({ if (query) floatArrayOf(0f, 1f) else floatArrayOf(1f, 0f) }, 4, 3f, 1)
        l.beginEnrollment(); l.feed(FloatArray(12) { .03f }, 0f)
        val original = l.centroid; query = true
        l.feed(FloatArray(24) { .03f }, 0f)
        assertSame(original, l.centroid)
        assertArrayEquals(floatArrayOf(1f, 0f), l.centroid!!, 0f)
    }
    @Test fun resetRequiresNewEnrollmentBeforeQuery() {
        val l = VoiceLearner({ floatArrayOf(1f, 0f) }, 4, 3f, 1)
        l.beginEnrollment(); l.feed(FloatArray(12) { .03f }, 0f)
        l.feed(FloatArray(12) { .03f }, 0f); l.reset()
        assertEquals(0, l.querySamplesBuffered)
        assertNull(l.feed(FloatArray(24) { .03f }, 0f)); assertFalse(l.learned)
    }
    @Test fun technicalAudioLineKeepsTimingAndDropFields() {
        val l = DiagnosticLog(2)
        val line = "audio " + "x".repeat(750) + " playbackUs=24 droppedAsr=0"
        l.add(line)
        assertEquals(line, l.snapshot())
        l.add("x".repeat(3000)); assertEquals(2048, l.snapshot().substringAfter('\n').length)
    }
}
