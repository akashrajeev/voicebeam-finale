package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.*
import org.junit.Assert.*
import org.junit.Test
class ConsentTest {
    private fun feed(g: ConsentGate, from: Long, a: Float, b: Float? = null) {
        var t = from
        repeat(20) { g.onFaces(t, if (b == null) mapOf(1 to a) else mapOf(1 to a, 2 to b)); t += 100 }
    }
    @Test fun phrases() {
        assertTrue(ConsentPhrases.isAgree("I AGREE")); assertTrue(ConsentPhrases.isAgree("Yes, I agree."))
        assertTrue(ConsentPhrases.isAgree("I consent"))
        assertFalse(ConsentPhrases.isAgree("I don't agree")); assertFalse(ConsentPhrases.isAgree("I disagree"))
        assertFalse(ConsentPhrases.isAgree("no I agree not")); assertFalse(ConsentPhrases.isAgree("we all agree that the weather is nice today ok"))
        assertFalse(ConsentPhrases.isAgree("agree"))
        assertTrue(ConsentPhrases.isWithdraw("I withdraw")); assertTrue(ConsentPhrases.isWithdraw("I do not agree"))
        assertFalse(ConsentPhrases.isWithdraw("stop it"))
    }
    @Test fun grantsWhenMouthMoved() {
        val g = ConsentGate(); g.request(1, 0); feed(g, 0, 0.8f)
        val r = g.onSpeech(2000, "I agree"); assertTrue(r is ConsentResult.Granted); assertEquals(ConsentPhase.GRANTED, g.phase)
    }
    @Test fun noLockWithoutPhrase() {
        val g = ConsentGate(); g.request(1, 0); feed(g, 0, 0.8f)
        assertTrue(g.onSpeech(2000, "hello there") is ConsentResult.Ignored); assertEquals(ConsentPhase.ASKING, g.phase)
    }
    @Test fun refusal() {
        val g = ConsentGate(); g.request(1, 0); feed(g, 0, 0.8f)
        assertTrue(g.onSpeech(2000, "no I do not agree") is ConsentResult.Refused); assertEquals(ConsentPhase.DECLINED, g.phase)
        assertTrue(g.onSpeech(2500, "I agree") is ConsentResult.Ignored); assertNotEquals(ConsentPhase.GRANTED, g.phase)
    }
    @Test fun stillMouthIsNotAccepted() {
        val g = ConsentGate(); g.request(1, 0); feed(g, 0, 0.05f)
        assertTrue(g.onSpeech(2000, "I agree") is ConsentResult.NeedsRetry); assertEquals(ConsentPhase.ASKING, g.phase)
    }
    @Test fun otherFaceTalkingIsAmbiguous() {
        val g = ConsentGate(); g.request(1, 0); feed(g, 0, 0.5f, 0.9f)
        assertTrue(g.onSpeech(2000, "I agree") is ConsentResult.NeedsRetry); assertEquals(ConsentPhase.ASKING, g.phase)
    }
    @Test fun faceNotVisible() {
        val g = ConsentGate(); g.request(1, 0)
        assertTrue(g.onSpeech(2000, "I agree") is ConsentResult.NeedsRetry)
    }
    @Test fun expires() {
        val g = ConsentGate(); g.request(1, 0); g.onFaces(25_000, mapOf(1 to 0.9f))
        assertEquals(ConsentPhase.DECLINED, g.phase); assertNull(g.faceId)
    }
    @Test fun withdrawSpokenAndButton() {
        val g = ConsentGate(); g.request(1, 0); feed(g, 0, 0.8f); g.onSpeech(2000, "I agree")
        assertTrue(g.onSpeech(5000, "I withdraw") is ConsentResult.Withdrawn); assertEquals(ConsentPhase.WITHDRAWN, g.phase)
        g.request(1, 6000); feed(g, 6000, 0.8f); g.onSpeech(8000, "I agree")
        assertTrue(g.withdraw("button") is ConsentResult.Withdrawn)
    }
    @Test fun logIsMinimalAndDeletable() {
        val f = java.io.File.createTempFile("consent", ".jsonl"); f.delete()
        val l = ConsentLog(f); l.add(1, "granted", 1, 0.8f, "I agree"); l.add(2, "withdrawn", 1, null, "x".repeat(100))
        assertEquals(2, l.count()); assertTrue(f.readText().lines()[1].length < 120)
        assertTrue(l.deleteAll()); assertEquals(0, l.count())
    }
}
