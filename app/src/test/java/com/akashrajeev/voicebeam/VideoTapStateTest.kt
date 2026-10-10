package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.VideoTapState.Source
import org.junit.Assert.*
import org.junit.Test

class VideoTapStateTest {
    private fun iv(a: Float, b: Float) = FootageAnalysis.Interval(a, b)
    private fun prop(s: TapProposal.Strength) = TapProposal.Proposal(iv(4f, 9f), s, 0.9f)

    @Test fun nothingGivesHintAndCannotStart() {
        val st = VideoTapState.resolve(null, null, 20f)
        assertEquals(Source.NONE, st.source); assertFalse(st.canStart); assertFalse(st.needsConfirm); assertEquals(FootageUiText.SUGGESTED_TAP_HINT, st.message)
    }
    @Test fun typedBeatsProposalAndStartsWhenValid() {
        val st = VideoTapState.resolve(prop(TapProposal.Strength.STRONG), iv(2.5f, 6.5f), 20f)
        assertEquals(Source.TYPED, st.source); assertTrue(st.canStart); assertFalse(st.needsConfirm); assertEquals(2.5f, st.interval!!.startSec, 0f)
    }
    @Test fun typedValidationBlocks() {
        assertFalse(VideoTapState.resolve(null, iv(1f, 2f), 20f).canStart)              // too short
        assertFalse(VideoTapState.resolve(null, iv(1f, 15f), 20f).canStart)             // too long
        assertFalse(VideoTapState.resolve(null, iv(18f, 25f), 20f).canStart)            // outside
        assertFalse(VideoTapState.resolve(null, iv(-1f, 4f), 20f).canStart)
        assertNull(VideoTapState.resolve(null, iv(5f, 4f), 20f).interval)
    }
    @Test fun proposalNeedsConfirmBeforeStart() {
        for (s in TapProposal.Strength.values()) {
            val before = VideoTapState.resolve(prop(s), null, 20f)
            assertTrue(before.needsConfirm); assertFalse(before.canStart); assertEquals(Source.PROPOSED, before.source)
            val after = VideoTapState.resolve(prop(s), null, 20f, userConfirmedProposal = true)
            assertFalse(after.needsConfirm); assertTrue(after.canStart)
        }
    }
    @Test fun weakMessageWarnsAboutOffScreenSpeaker() {
        assertTrue(VideoTapState.resolve(prop(TapProposal.Strength.WEAK), null, 20f).message.contains("off screen"))
        assertFalse(VideoTapState.resolve(prop(TapProposal.Strength.STRONG), null, 20f).message.contains("off screen"))
    }
    @Test(expected = IllegalArgumentException::class) fun badDurationRejected() { VideoTapState.resolve(null, null, 0f) }
}
