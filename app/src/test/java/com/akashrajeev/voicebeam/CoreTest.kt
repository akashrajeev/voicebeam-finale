package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.Box
import com.akashrajeev.voicebeam.core.CaptionSegment
import com.akashrajeev.voicebeam.core.Captions
import com.akashrajeev.voicebeam.core.EnergyVad
import com.akashrajeev.voicebeam.core.FaceObservation
import com.akashrajeev.voicebeam.core.FaceTracker
import com.akashrajeev.voicebeam.core.FillCenterMapper
import com.akashrajeev.voicebeam.core.GateInputs
import com.akashrajeev.voicebeam.core.LipActivity
import com.akashrajeev.voicebeam.core.TargetGate
import com.akashrajeev.voicebeam.core.VoiceMatch
import com.akashrajeev.voicebeam.core.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class CoreTest {

    @Test fun captionsTidyFixesCaseAndI() {
        assertEquals("I think i'm late".replace("i'm", "I'm"), Captions.tidy("  I THINK I'M   LATE "))
        assertEquals("", Captions.tidy("   "))
    }

    @Test fun srtFormat() {
        val srt = Captions.toSrt(listOf(CaptionSegment(1500, 3250, "Hello there", true), CaptionSegment(4000, 4000, "Hi", false)))
        assertTrue(srt.startsWith("1\n00:00:01,500 --> 00:00:03,250\nHello there\n\n2\n00:00:04,000 --> 00:00:05,000\nHi"))
        val onlyTarget = Captions.toSrt(listOf(CaptionSegment(0, 1000, "A", true), CaptionSegment(1000, 2000, "B", false)), includeOthers = false)
        assertTrue(onlyTarget.contains("A") && !onlyTarget.contains("B"))
        assertEquals("01:02:03,004", Captions.srtTime(3_723_004))
    }

    @Test fun transcriptAndActiveAt() {
        val segs = listOf(CaptionSegment(0, 1000, "One", true), CaptionSegment(5000, 6000, "Two", false))
        assertEquals("[00:00] Speaker 1: One\n[00:05] Others: Two", Captions.toText(segs))
        assertEquals("One", Captions.activeAt(segs, 500)?.text)
        assertEquals("One", Captions.activeAt(segs, 2000)?.text)   // lingers
        assertNull(Captions.activeAt(segs, 3500))
        assertEquals("Two", Captions.activeAt(segs, 5200)?.text)
    }

    @Test fun wavRoundTrip() {
        val f = File.createTempFile("vbtest", ".wav")
        val n = 16000
        val tone = FloatArray(n) { (0.5 * sin(2 * PI * 440 * it / 16000.0)).toFloat() }
        WavWriter(f, 16000).use { it.write(tone) }
        assertEquals(44L + n * 2, f.length())
        val (back, sr) = WavWriter.read(f)
        assertEquals(16000, sr)
        assertEquals(n, back.size)
        for (i in 0 until n step 997) assertEquals(tone[i], back[i], 1e-3f)
        f.delete()
    }

    @Test fun fillCenterMapperRoundTrip() {
        for (mirror in listOf(false, true)) {
            val m = FillCenterMapper(480f, 640f, 1080f, 2000f, mirror)
            val (vx, vy) = m.toView(0.3f, 0.6f)
            val (nx, ny) = m.toImage(vx, vy)
            assertEquals(0.3f, nx, 1e-4f); assertEquals(0.6f, ny, 1e-4f)
        }
        // Mirroring flips x.
        val a = FillCenterMapper(480f, 640f, 480f, 640f, false).toView(0.2f, 0.5f).first
        val b = FillCenterMapper(480f, 640f, 480f, 640f, true).toView(0.2f, 0.5f).first
        assertEquals(480f - a, b, 1e-3f)
    }

    @Test fun lipActivityTalkingVsStill() {
        val still = LipActivity(); val talk = LipActivity()
        for (i in 0 until 30) {
            val t = i * 33L
            still.add(t, 0.02f + (if (i % 2 == 0) 0.001f else 0f))
            talk.add(t, 0.02f + 0.06f * ((sin(i * 1.3) + 1) / 2).toFloat())
        }
        assertTrue(still.score() < 0.15f)
        assertTrue(talk.score() > 0.6f)
    }

    @Test fun trackerKeepsIdsAndLock() {
        val tr = FaceTracker()
        tr.update(0, listOf(FaceObservation(Box(0.1f, 0.1f, 0.3f, 0.4f), 0.02f), FaceObservation(Box(0.6f, 0.1f, 0.8f, 0.4f), 0.02f)))
        val id = tr.lockAt(0.2f, 0.25f, 10)
        assertNotNull(id)
        // Person moves a bit to the right; lock must follow the same id.
        var faces = emptyList<com.akashrajeev.voicebeam.core.TrackedFace>()
        for (k in 1..10) faces = tr.update(k * 33L, listOf(
            FaceObservation(Box(0.1f + k * 0.01f, 0.1f, 0.3f + k * 0.01f, 0.4f), 0.02f),
            FaceObservation(Box(0.6f, 0.1f, 0.8f, 0.4f), 0.02f)))
        assertEquals(id, tr.lockedId)
        val locked = faces.first { it.id == id }
        assertEquals(0.3f, locked.box.cx, 0.02f)
        // Tap on empty space far away does not lock anything.
        assertNull(tr.lockAt(0.5f, 0.95f, 400))
    }

    @Test fun lockSurvivesShortDropout() {
        val tr = FaceTracker()
        tr.update(0, listOf(FaceObservation(Box(0.4f, 0.4f, 0.6f, 0.6f), 0.02f)))
        val id = tr.lockAt(0.5f, 0.5f, 0)
        tr.update(1000, emptyList())          // face hidden for a second
        tr.update(1300, listOf(FaceObservation(Box(0.42f, 0.4f, 0.62f, 0.6f), 0.02f)))
        assertEquals(id, tr.lockedId)
        assertEquals(id, tr.snapshot(1300).single().id)
    }

    @Test fun gateLetsTargetThroughAndQuietsOthers() {
        val g = TargetGate(frameMs = 16f); g.quietOthers = 0.8f
        val target = GateInputs(hasLock = true, lockedSpeaking = 0.9f, othersSpeaking = 0f, voiceMatch = null, voiceActive = true)
        repeat(50) { g.process(target) }
        assertTrue("target gain ${g.gain}", g.gain > 0.9f)
        val other = GateInputs(hasLock = true, lockedSpeaking = 0.05f, othersSpeaking = 0.9f, voiceMatch = 0.1f, voiceActive = true)
        repeat(200) { g.process(other) }
        assertTrue("other gain ${g.gain}", g.gain < 0.3f)
        // With no lock, everyone is heard.
        val free = TargetGate(); free.quietOthers = 1f
        repeat(50) { free.process(other.copy(hasLock = false)) }
        assertTrue(free.gain > 0.95f)
    }

    @Test fun gateHoldsThroughPauses() {
        val g = TargetGate(frameMs = 10f); g.quietOthers = 1f
        val talking = GateInputs(true, 0.9f, 0f, null, true)
        repeat(50) { g.process(talking) }
        val pause = GateInputs(true, 0f, 0f, null, false)
        repeat(10) { g.process(pause) }     // 100 ms pause, inside the 300 ms hold
        assertTrue(g.gain > 0.85f)
    }

    @Test fun hiddenLockedFaceDoesNotOpenForUnknownSpeech() {
        val g = TargetGate()
        repeat(5) { g.targetProbability(GateInputs(true, 0f, 0f, null, true, lockedVisible = false)) } // settle hysteresis
        val p = g.targetProbability(GateInputs(true, 0f, 0f, null, true, lockedVisible = false))
        assertEquals(0f, p, 0f)
    }

    @Test fun audioOnlyNeverUsesStaleLipOrUnverifiedVoice() {
        val gate = TargetGate()
        repeat(5) { gate.targetProbability(GateInputs(true, 0.99f, 0f, null, true, audioOnly = true)) }
        assertEquals(0f, gate.targetProbability(GateInputs(true, 0.99f, 0f, null, true, audioOnly = true)), 0.001f)
        val gate2 = TargetGate()
        repeat(5) { gate2.targetProbability(GateInputs(true, 0.99f, 0.99f, 0.1f, true, audioOnly = true)) }
        assertEquals(0.1f, gate2.targetProbability(GateInputs(true, 0.99f, 0.99f, 0.1f, true, audioOnly = true)), 0.001f)
        val gate3 = TargetGate()
        repeat(5) { gate3.targetProbability(GateInputs(true, 0f, 0f, 0.91f, true, audioOnly = true)) }
        assertEquals(0.91f, gate3.targetProbability(GateInputs(true, 0f, 0f, 0.91f, true, audioOnly = true)), 0.001f)
    }

    @Test fun voiceMatchScores() {
        val a = floatArrayOf(1f, 0f, 0f); val b = floatArrayOf(0.9f, 0.1f, 0f); val c = floatArrayOf(0f, 1f, 0f)
        assertTrue(VoiceMatch.score(VoiceMatch.cosine(a, b)) > 0.9f)
        assertEquals(0f, VoiceMatch.score(VoiceMatch.cosine(a, c)), 1e-6f)
        val avg = VoiceMatch.average(listOf(a, c))
        assertEquals(0.5f, avg[0], 1e-6f)
    }

    @Test fun vadSeparatesSpeechFromSilence() {
        val vad = EnergyVad()
        val quiet = FloatArray(256) { 0.001f * sin(it * 0.3f) }
        repeat(100) { vad.isVoice(quiet) }
        assertTrue(!vad.isVoice(quiet))
        val loud = FloatArray(256) { 0.2f * sin(it * 0.3f) }
        assertTrue(vad.isVoice(loud))
    }
}
