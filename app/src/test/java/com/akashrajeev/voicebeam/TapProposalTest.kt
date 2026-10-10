package com.akashrajeev.voicebeam.core

import org.junit.Assert.*
import org.junit.Test

class TapProposalTest {
    private val dur = 20f
    private val bins = 40
    private fun speechAll(on: Boolean = true) = BooleanArray((16000 * 20 + 511) / 512) { on }
    private fun lips(vararg r: IntRange) = BooleanArray(bins).also { a -> for (x in r) for (i in x) a[i] = true }
    private val quiet = BooleanArray(bins) { true }

    @Test fun picksTheLongRunWithinBounds() {
        val p = TapProposal.propose(lips(4..9, 14..33), quiet, speechAll(), dur)!!
        val len = p.interval.endSec - p.interval.startSec
        assertTrue(len >= TapProposal.MIN_SEC && len <= TapProposal.MAX_SEC)
        assertTrue(p.interval.startSec >= 7f && p.interval.endSec <= 17f)           // inside the 14..33 run (7.0-17.0 s), the 6-bin run is too short
        assertEquals(TapProposal.Strength.STRONG, p.strength)
    }
    @Test fun tooShortRunGivesNothing() { assertNull(TapProposal.propose(lips(4..8), quiet, speechAll(), dur)) }   // 2.5 s
    @Test fun otherLipsOnBlocks() {
        val others = BooleanArray(bins) { it !in 10..30 }                          // another face is talking 10..30
        assertNull(TapProposal.propose(lips(8..33), others, speechAll(), dur).takeIf { it != null && it.interval.startSec >= 5f && it.interval.endSec <= 15f })
    }
    @Test fun noSpeechNoProposal() { assertNull(TapProposal.propose(lips(4..30), quiet, speechAll(false), dur)) }
    @Test fun unknownOthersIsWeak() {
        val p = TapProposal.propose(lips(4..20), null, speechAll(), dur)!!
        assertEquals(TapProposal.Strength.WEAK, p.strength)
    }
    @Test fun intervalIsInsideTheClip() {
        val p = TapProposal.propose(lips(0..39), quiet, speechAll(), dur)!!
        assertTrue(p.interval.startSec >= 0f && p.interval.endSec <= dur)
        assertTrue(p.interval.endSec - p.interval.startSec <= TapProposal.MAX_SEC)
    }
    @Test fun prefersTheMoreSpeechyWindow() {
        val sp = speechAll(); for (f in (16000 * 5 / 512)..(16000 * 12 / 512)) sp[f] = false     // 5-12 s is silent
        val p = TapProposal.propose(lips(0..39), quiet, sp, dur)!!
        assertTrue(p.interval.startSec >= 12f - 0.01f || p.interval.endSec <= 5f + 0.01f)       // never straddles the silent stretch
    }
    @Test(expected = IllegalArgumentException::class) fun badLipLengthRejected() { TapProposal.propose(BooleanArray(5), null, speechAll(), dur) }
}
