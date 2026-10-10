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

    private val f1 = 1 to Box(0.2f, 0.2f, 0.4f, 0.5f)   // width 0.2, centre (0.3, 0.35)
    private val f2 = 2 to Box(0.6f, 0.2f, 0.8f, 0.5f)
    private fun hold(g: ConsentGate, hand: List<HandObservation>, from: Long, ms: Long, faces: List<Pair<Int, Box>> = listOf(f1, f2)): ConsentResult {
        var t = from; var last: ConsentResult = ConsentResult.Ignored
        while (t <= from + ms) { val r = g.onHands(t, hand, faces); if (r !is ConsentResult.Ignored) last = r; t += 100 }
        return last
    }
    private val up = HandObservation("Thumb_Up", 0.9f, 0.4f, 0.55f)
    @Test fun thumbsUpHeldGrants() {
        val g = ConsentGate(); g.request(1, 0)
        assertTrue(hold(g, listOf(up), 0, 1200) is ConsentResult.Granted); assertEquals(ConsentPhase.GRANTED, g.phase)
    }
    @Test fun shortThumbsUpDoesNot() {
        val g = ConsentGate(); g.request(1, 0)
        assertTrue(hold(g, listOf(up), 0, 600) is ConsentResult.Ignored); assertEquals(ConsentPhase.ASKING, g.phase)
        assertTrue(g.gestureProgress > 0.4f)
    }
    @Test fun brokenHoldResets() {
        val g = ConsentGate(); g.request(1, 0)
        hold(g, listOf(up), 0, 700)
        hold(g, listOf(HandObservation("Open_Palm", 0.9f, 0.4f, 0.55f)), 800, 500)
        assertEquals(0f, g.gestureProgress, 0f)
        assertTrue(hold(g, listOf(up), 900, 600) is ConsentResult.Ignored)
    }
    @Test fun twoHandsRejected() {
        val g = ConsentGate(); g.request(1, 0)
        assertTrue(hold(g, listOf(up, up), 0, 1500) is ConsentResult.Ignored); assertEquals(ConsentPhase.ASKING, g.phase)
    }
    @Test fun handNearOtherFaceRejected() {
        val g = ConsentGate(); g.request(1, 0)
        val other = HandObservation("Thumb_Up", 0.9f, 0.55f, 0.55f)   // closer to face 2
        assertTrue(hold(g, listOf(other), 0, 1500) is ConsentResult.Ignored)
    }
    @Test fun handTooFarRejected() {
        val g = ConsentGate(); g.request(1, 0)
        assertTrue(hold(g, listOf(HandObservation("Thumb_Up", 0.9f, 0.3f, 0.95f)), 0, 1500, listOf(f1)) is ConsentResult.Ignored)
    }
    @Test fun lowScoreRejected() {
        val g = ConsentGate(); g.request(1, 0)
        assertTrue(hold(g, listOf(HandObservation("Thumb_Up", 0.4f, 0.4f, 0.55f)), 0, 1500) is ConsentResult.Ignored)
    }
    @Test fun faceGoneRejected() {
        val g = ConsentGate(); g.request(1, 0)
        assertTrue(hold(g, listOf(up), 0, 1500, listOf(f2)) is ConsentResult.Ignored)
    }
    @Test fun thumbsDownWithdraws() {
        val g = ConsentGate(); g.request(1, 0); hold(g, listOf(up), 0, 1200)
        val down = HandObservation("Thumb_Down", 0.9f, 0.4f, 0.55f)
        assertTrue(hold(g, listOf(down), 2000, 1200) is ConsentResult.Withdrawn); assertEquals(ConsentPhase.WITHDRAWN, g.phase)
    }
    @Test fun thumbsUpIgnoredWhenIdle() {
        val g = ConsentGate()
        assertTrue(hold(g, listOf(up), 0, 1500) is ConsentResult.Ignored); assertEquals(ConsentPhase.IDLE, g.phase)
    }
    @Test fun requireBothGestureThenSpeech() {
        val g = ConsentGate(); g.requireBoth = true; g.request(1, 0)
        assertTrue(hold(g, listOf(up), 0, 1200) is ConsentResult.NeedsRetry); assertEquals(ConsentPhase.ASKING, g.phase)
        feed(g, 1000, 0.8f)
        assertTrue(g.onSpeech(3000, "I agree") is ConsentResult.Granted)
    }
    @Test fun requireBothSpeechThenGestureAndTimeout() {
        val g = ConsentGate(); g.requireBoth = true; g.request(1, 0); feed(g, 0, 0.8f)
        assertTrue(g.onSpeech(2000, "I agree") is ConsentResult.NeedsRetry)
        assertTrue(hold(g, listOf(up), 2500, 1200) is ConsentResult.Granted)
        val h = ConsentGate(); h.requireBoth = true; h.request(1, 0); feed(h, 0, 0.8f); h.onSpeech(2000, "I agree")
        assertTrue(hold(h, listOf(up), 9000, 1200) is ConsentResult.NeedsRetry)   // too late (>5 s)
        assertEquals(ConsentPhase.ASKING, h.phase)
    }
    @Test fun eitherPathWorksByDefault() {
        val g = ConsentGate(); g.request(1, 0); feed(g, 0, 0.8f)
        assertTrue(g.onSpeech(2000, "I agree") is ConsentResult.Granted)
    }

    @Test fun flickerWithinGapKeepsHold() {
        val g = ConsentGate(); g.request(1, 0)
        var t = 0L; var last: ConsentResult = ConsentResult.Ignored
        while (t <= 1500) {
            val h = if (t == 500L || t == 600L) emptyList() else listOf(up)   // 2 missed frames = 200 ms
            val r = g.onHands(t, h, listOf(f1, f2)); if (r !is ConsentResult.Ignored) last = r; t += 100
        }
        assertTrue(last is ConsentResult.Granted)
    }
}
