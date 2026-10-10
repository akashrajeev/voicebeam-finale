package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.*
import org.junit.Assert.*
import org.junit.Test
class FullStrictTest {
    private fun inputs(active: Boolean = false) = GateInputs(true, 0f, 0f, .4f, active, voiceLearned = true)
    private fun gate(full: Boolean = true) = TargetGate().apply { tuning = GateTuning(strictEnabled = true, strictFull = full) }
    private fun settle(g: TargetGate, i: GateInputs): Float { repeat(200) { g.process(i) }; return g.gain }
    @Test fun fullDucksMusicAndRoomToneWithVadFalse() { assertEquals(.2f, settle(gate(), inputs()), .002f) }
    @Test fun speechOnlyStillPassesQuiet() { assertEquals(1f, settle(gate(false), inputs()), .002f) }
    @Test fun unlockedPassesEverything() { assertEquals(1f, settle(gate(), inputs().copy(hasLock = false)), .002f) }
    @Test fun unlearnedPassesEverything() { assertEquals(1f, settle(gate(), inputs().copy(voiceLearned = false)), .002f) }
    @Test fun targetAndShortQuietHangoverStayOpenThenExpire() {
        val g=gate();val target=inputs(true).copy(lockedSpeaking = .8f, voiceMatch = .95f)
        assertTrue(settle(g,target) > .9f)
        repeat(30) { g.process(inputs()) };assertTrue(g.gain > .9f)
        assertEquals(.2f,settle(g,inputs()),.002f)
    }
    @Test fun explicitOtherKeepsStrongerAttenuation() { assertEquals(.15f,settle(gate(),inputs(true).copy(voiceMatch = .1f)),.002f) }
    @Test fun overlapAndFaceLossDuckWithoutClaimingSeparation() {
        assertEquals(.2f,settle(gate(), inputs(true).copy(lockedSpeaking = .8f, othersSpeaking = .8f)),.002f)
        assertEquals(.2f,settle(gate(), inputs().copy(lockedVisible = false)),.002f)
    }
    @Test fun envelopeFallIsSlowAndTargetRiseFast() {
        val g=gate();val target=inputs(true).copy(lockedSpeaking = .8f,voiceMatch = .95f)
        val first=g.process(inputs());assertTrue(first > .9f)
        settle(g,inputs());repeat(8){g.process(target)};assertTrue(g.gain > .85f)
    }
    @Test fun disablingStrictRestoresQuietPassAndResetClearsHangover() {
        val g=gate();settle(g,inputs());g.tuning=g.tuning.copy(strictEnabled=false)
        assertEquals(1f,settle(g,inputs()),.002f)
        g.reset();assertEquals(TargetState.UNLOCKED,g.state)
    }
}
