package com.akashrajeev.voicebeam.core

import org.junit.Assert.*
import org.junit.Test

class FootageUiTextTest {
    @Test fun everyReasonHasText() { for (r in AbstainReason.values()) assertTrue(r.name, FootageUiText.reasonText(r).isNotBlank()) }
    @Test fun stagesKnownAndUnknown() {
        for (s in listOf("COPY", "DECODE", "VAD", "DENOISE", "EMBED", "EXTRACT", "RENDER", "MUX")) assertNotEquals("Working", FootageUiText.stageLabel(s))
        assertEquals("Working", FootageUiText.stageLabel("NOPE"))
    }
    @Test fun noOverclaimingWords() {
        val all = (FootageUiText.STEPS + AbstainReason.values().map { FootageUiText.reasonText(it) } + FootageUiText.SUGGESTED_TAP_HINT + FootageUiText.ORIGINAL_KEPT).joinToString(" ").lowercase()
        assertFalse(all.contains("perfect")); assertFalse(all.contains("guarantee")); assertFalse(all.contains("studio"))
    }
    @Test fun stepsMentionOriginalAndOnDevice() {
        val t = FootageUiText.STEPS.joinToString(" ").lowercase(); assertTrue(t.contains("original")); assertTrue(t.contains("phone"))
    }
}
