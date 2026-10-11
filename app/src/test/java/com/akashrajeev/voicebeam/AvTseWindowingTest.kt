package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.AvTseWindowing
import org.junit.Assert.*
import org.junit.Test

class AvTseWindowingTest {
    @Test fun thirtySecondsIsSixteenWindows() = assertEquals(16, AvTseWindowing.starts(16000 * 30).size)
    @Test fun shortClipIsOneWindow() = assertEquals(listOf(0), AvTseWindowing.starts(40000))
    @Test fun lastWindowEndsAtClipEnd() {
        val s = AvTseWindowing.starts(16000 * 10)
        assertEquals(0, s[0]); assertEquals(16000 * 10 - 48000, s.last())
    }
}
