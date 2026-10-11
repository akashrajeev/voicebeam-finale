package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.DeepgramTranscript
import org.junit.Assert.*
import org.junit.Test
class DeepgramTranscriptTest {
    @Test fun interimThenFinalThenEnd() {
        val t = DeepgramTranscript()
        t.onResult("hello wor", false, false)
        assertEquals(Pair("hello wor", false), t.poll())
        t.onResult("Hello world.", true, false)
        assertEquals(Pair("Hello world.", false), t.poll())
        t.onResult("how are", false, false)
        assertEquals(Pair("Hello world. how are", false), t.poll())
        t.onResult("How are you?", true, true)
        assertEquals(Pair("Hello world. How are you?", true), t.poll())
        assertEquals(Pair("", false), t.poll())
    }
    @Test fun utteranceEndClosesAndEmptyDoesNothing() {
        val t = DeepgramTranscript()
        t.onUtteranceEnd()
        assertEquals(Pair("", false), t.poll())
        t.onResult("Good morning", true, false)
        t.onUtteranceEnd()
        assertEquals(Pair("Good morning", true), t.poll())
    }
    @Test fun resetClears() {
        val t = DeepgramTranscript(); t.onResult("x", true, true); t.reset()
        assertEquals(Pair("", false), t.poll())
    }
}
