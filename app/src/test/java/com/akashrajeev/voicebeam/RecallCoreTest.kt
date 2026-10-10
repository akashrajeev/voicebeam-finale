package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.recall.*
import org.junit.Assert.*
import org.junit.Test

class RecallCoreTest {
    @Test fun fullChunkHasOverlapAndPartialIsRetained() {
        val chunks=mutableListOf<Pair<Long,FloatArray>>()
        val c=RecallChunker(rate=10,seconds=25,overlap=1)
        c.add(FloatArray(301) { it.toFloat() },301) { offset,a -> chunks+=offset to a }
        c.finish { offset,a -> chunks+=offset to a }
        assertEquals(2,chunks.size);assertEquals(250,chunks[0].second.size)
        assertEquals(24000L,chunks[1].first);assertEquals(61,chunks[1].second.size)
        assertEquals(240f,chunks[1].second.first(),0f);assertEquals(300f,chunks[1].second.last(),0f)
    }
    @Test fun exactWindowDoesNotDuplicateOverlapAsFinalClip() {
        val chunks=mutableListOf<FloatArray>();val c=RecallChunker(rate=10)
        c.add(FloatArray(250),250) { _,a -> chunks+=a };c.finish { _,a -> chunks+=a }
        assertEquals(1,chunks.size)
    }
    @Test fun arbitraryInputBlocksLoseNoSamples() {
        val chunks=mutableListOf<Pair<Long,FloatArray>>();val c=RecallChunker(rate=10)
        var value=0
        repeat(73) { c.add(FloatArray(7) { (value++).toFloat() },7) { offset,a -> chunks+=offset to a } }
        c.finish { offset,a -> chunks+=offset to a }
        val reconstruct=mutableMapOf<Int,Float>()
        chunks.forEach { (offset,a) -> a.forEachIndexed { i,v -> reconstruct[(offset/100).toInt()+i]=v } }
        assertEquals(511,reconstruct.size)
        repeat(511) { assertEquals(it.toFloat(),reconstruct[it]!!,0f) }
        assertTrue(chunks.all { it.second.size<=300 })
    }
    @Test fun unsupportedGeneratedFactIsRejected() {
        assertFalse(RecallGrounding.isExactQuote("Due Saturday", "Due Friday"))
        assertFalse(RecallGrounding.isExactQuote("", "Anything"))
        assertTrue(RecallGrounding.isExactQuote("Due Friday", "Please submit. Due Friday at 5."))
    }
    @Test fun cosineHandlesZeroAndIdentity() {
        assertEquals(0f,RecallGrounding.cosine(floatArrayOf(0f,0f),floatArrayOf(1f,2f)),0f)
        assertEquals(1f,RecallGrounding.cosine(floatArrayOf(1f,2f),floatArrayOf(1f,2f)),0.00001f)
    }
    @Test(expected=IllegalArgumentException::class) fun mismatchedVectorsFailClosed() {
        RecallGrounding.cosine(floatArrayOf(1f),floatArrayOf(1f,2f))
    }
    @Test(expected=IllegalArgumentException::class) fun clipsOverAudioLimitAreRejected() { RecallChunker(seconds=31) }
    @Test fun oversizedEmbeddingInputIsSplitWithoutLoss() {
        val text="Malayalam മലയാളം Gemma 🌟 ".repeat(500)
        val parts=RecallTextSlices.split(text)
        assertEquals(text,parts.joinToString(""))
        assertTrue(parts.size>1)
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size<=600 })
        assertTrue(parts.none { it.contains('\uFFFD') })
    }
    @Test fun nearSilentGateDoesNotRejectQuietAudibleSamples() {
        assertTrue(RecallAudioEnergy.nearSilent(FloatArray(16000)))
        assertFalse(RecallAudioEnergy.nearSilent(FloatArray(16000) { 0.003f }))
        val click=FloatArray(16000);click[300]=0.03f
        assertFalse(RecallAudioEnergy.nearSilent(click))
    }
    @Test fun repetitionLoopRejectedButHindiRephrasingAllowed() {
        assertTrue(RecallTranscriptQuality.repeatedLoop("I'm going to do a little bit of a dance ".repeat(10)))
        assertFalse(RecallTranscriptQuality.repeatedLoop("मैं एक भारतीय नागरिक हूँ और मैं भारत में एक ऑनलाइन गेमिंग टूर्नामेंट में भाग लेना चाहता हूँ। मैं जानना चाहता हूँ कि क्या मैं एक भारतीय नागरिक के रूप में इस टूर्नामेंट में भाग ले सकता हूँ।"))
        assertFalse(RecallTranscriptQuality.repeatedLoop("Please speak clearly. Please speak clearly."))
    }

    private fun clip(id: Long,start: Long,text: String,session: Long=1,duration: Long=25000) = RecallSegment(id,session,start,duration,"audio-$id",text,"ready","")
    @Test fun durationUsesTimelineNotSummedOverlaps() {
        assertEquals(73000L,RecallConversation.duration(listOf(clip(1,0,"a"),clip(2,24000,"b"),clip(3,48000,"c"))))
        assertEquals(0L,RecallConversation.duration(emptyList()))
    }
    @Test fun clocksIncludeHoursAndClampNegative() {
        assertEquals("0:00",RecallConversation.clock(-1));assertEquals("1:13",RecallConversation.clock(73999))
        assertEquals("1:01:01",RecallConversation.clock(3661000))
    }
    @Test fun overlapDedupedWithoutChangingRawTranscript() {
        val a=clip(1,0,"We discussed AI in education.");val b=clip(2,24000,"AI in education, healthcare and transport.")
        val entries=RecallConversation.transcript(listOf(b,a))
        assertEquals("healthcare and transport.",entries[1].text)
        assertEquals("AI in education, healthcare and transport.",b.text)
    }
    @Test fun nonOverlappingRepeatedWordsRetained() {
        val entries=RecallConversation.transcript(listOf(clip(1,0,"AI in education"),clip(2,25000,"AI in education matters")))
        assertEquals("AI in education matters",entries[1].text)
    }
    @Test fun differentSessionsAreNotDeduped() {
        val entries=RecallConversation.transcript(listOf(clip(1,0,"AI in education"),clip(2,24000,"AI in education matters",2)))
        assertEquals("AI in education matters",entries[1].text)
    }
    @Test fun oneWordOverlapIsNotEnoughToRemoveSpeech() {
        val entries=RecallConversation.transcript(listOf(clip(1,0,"I agree yes"),clip(2,24000,"yes I disagree")))
        assertEquals("yes I disagree",entries[1].text)
    }
    @Test fun wholeRepeatedClipNotErased() {
        assertEquals(2,RecallConversation.transcript(listOf(clip(1,0,"This is true"),clip(2,24000,"This is true"))).size)
    }
    @Test fun missingTranscriptDoesNotJoinAcrossGap() {
        val entries=RecallConversation.transcript(listOf(clip(1,0,"AI in education"),clip(2,24000,""),clip(3,48000,"AI in education matters")))
        assertEquals("AI in education matters",entries[1].text)
    }
    @Test fun recapQueriesDoNotUseOnlyTopK() {
        assertTrue(RecallConversation.summaryQuestion("so what did we do today tell me"))
        assertTrue(RecallConversation.summaryQuestion("summarize our conversation"))
        assertTrue(RecallConversation.summaryQuestion("What topics did we talk about?"))
        assertTrue(RecallConversation.summaryQuestion("what did we speak about today"))
        assertTrue(RecallConversation.summaryQuestion("what are we speaking about today"))
        assertTrue(RecallConversation.summaryQuestion("what we spoke about today"))
        assertFalse(RecallConversation.summaryQuestion("When is the train?"))
    }
    @Test fun fallbackPointsAreSourceSubstringsNotInventedActions() {
        val text="AI is useful. We discussed education. We discussed education."
        val points=RecallConversation.keyPoints(text)
        assertEquals(2,points.size);assertTrue(points.all { RecallGrounding.isExactQuote(it,text) })
        assertTrue(RecallConversation.keyPoints("").isEmpty())
    }
}
