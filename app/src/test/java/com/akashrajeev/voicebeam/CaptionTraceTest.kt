package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.CaptionTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CaptionTraceTest {
    @Test fun modelLine() {
        assertEquals("captionModel=moonshine-base-en-int8", CaptionTrace.model("moonshine-base-en-int8"))
    }
    @Test fun utteranceLineHasPathAndFallbackButNoText() {
        val line = CaptionTrace.utterance("moonshine-base-en-int8", "device", 420L, 3200L, 25, "groq_timeout")
        assertEquals("captionUtterance model=moonshine-base-en-int8 path=device ms=420 audioMs=3200 chars=25 fallback=groq_timeout", line)
    }
    @Test fun noFallbackOmitsField() {
        assertFalse(CaptionTrace.utterance("m", "groq", 1L, 2L, 3, null).contains("fallback"))
    }
    @Test fun fieldsAreSanitised() {
        val line = CaptionTrace.utterance("bad name\nsecret=abc", "device", 1L, 1L, 1, null)
        assertFalse(line.contains("\n")); assertFalse(line.contains(" secret"))
    }
}
