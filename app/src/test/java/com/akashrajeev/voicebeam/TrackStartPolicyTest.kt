package com.akashrajeev.voicebeam.core

import org.junit.Assert.*
import org.junit.Test

class TrackStartPolicyTest {
    @Test fun negativeStartIsClampedNotRejected() {
        assertEquals(0L, TrackStartPolicy.clamp(-66_667L)); assertEquals(1234L, TrackStartPolicy.clamp(1234L))
    }
    @Test fun offsetUsesClampedStarts() {
        assertEquals(0L, TrackStartPolicy.offsetUs(0L, -66_667L))             // B-frame video start: not an offset
        assertEquals(20_000L, TrackStartPolicy.offsetUs(20_000L, -66_667L))
        assertEquals(-30_000L, TrackStartPolicy.offsetUs(0L, 30_000L))
    }
    @Test fun offsetStaysInsideTheAlignmentCapForTypicalNegatives() {
        AudioAlignment.leadingSamples(TrackStartPolicy.offsetUs(-100_000L, -66_667L), 16000)   // must not throw
    }
    @Test fun summaryNamesTheTracks() {
        assertEquals("tracks: video/avc, audio/mp4a-latm", TrackStartPolicy.summary(listOf("video/avc", "audio/mp4a-latm")))
        assertEquals("no tracks", TrackStartPolicy.summary(emptyList()))
    }
}
