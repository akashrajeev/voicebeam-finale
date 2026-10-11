package com.akashrajeev.voicebeam.core

import org.junit.Assert.*
import org.junit.Test

class FaceProposalResultTest {
    private val found = FaceProposalResult.Found(TapProposal.Proposal(FootageAnalysis.Interval(1f, 5f), TapProposal.Strength.STRONG, 0.9f))

    @Test fun threeOutcomesHaveThreeDifferentMessages() {
        val msgs = listOf(FaceProposalResult.userText(found), FaceProposalResult.userText(FaceProposalResult.NoWindow), FaceProposalResult.userText(FaceProposalResult.Failed("boom")))
        assertEquals(3, msgs.toSet().size)
    }
    @Test fun failureNeverSaysThereIsNoSoloMoment() {
        val t = FaceProposalResult.userText(FaceProposalResult.Failed("Video decode timed out"))
        assertFalse(t.contains("only this person speaks")); assertTrue(t.contains("Video decode timed out"))
    }
    @Test fun noAudioTrackGetsAPlainExplanation() {
        val t = FaceProposalResult.failedText("Choose a video with an audio track")
        assertTrue(t.contains("no sound track")); assertFalse(t.contains("only this person speaks"))
    }
    @Test fun noWindowDoesNotBlameTheVideo() {
        val t = FaceProposalResult.userText(FaceProposalResult.NoWindow)
        assertTrue(t.contains("only this person speaks")); assertFalse(t.contains("Couldn't"))
    }
    @Test fun blankReasonStillReadable() { assertTrue(FaceProposalResult.failedText("  ").contains("unknown error")) }
}
