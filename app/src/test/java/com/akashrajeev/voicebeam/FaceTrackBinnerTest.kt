package com.akashrajeev.voicebeam.core

import org.junit.Assert.*
import org.junit.Test

class FaceTrackBinnerTest {
    private val dur = 20f
    private val left = Box(0.10f, 0.30f, 0.30f, 0.60f)
    private val right = Box(0.65f, 0.30f, 0.85f, 0.60f)
    private fun talking(i: Int) = if (i % 2 == 0) 0.01f else 0.06f
    private val still = 0.02f

    /** 10 fps; A talks 0-8 s, B talks 10-18 s; both visible the whole time. */
    private fun feed(b: FaceTrackBinner, until: Float = dur, bVisible: Boolean = true) {
        var i = 0
        var t = 0
        while (t < (until * 1000).toInt()) {
            val s = t / 1000f
            val a = FaceObservation(left, if (s < 8f) talking(i) else still)
            val bb = FaceObservation(right, if (s in 10f..18f) talking(i) else still)
            b.add(t.toLong(), if (bVisible) listOf(a, bb) else listOf(a))
            i++; t += 100
        }
    }

    @Test fun tapResolvesToTheFaceUnderTheFinger() {
        val b = FaceTrackBinner(dur); feed(b, 3f)
        val a = b.idAt(0.2f, 0.45f, 2f); val c = b.idAt(0.75f, 0.45f, 2f)
        assertNotNull(a); assertNotNull(c); assertNotEquals(a, c)
        assertNull(b.idAt(0.5f, 0.95f, 2f))                              // empty area far from any face
    }

    @Test fun strongProposalForTheTalkerWithQuietOthers() {
        val b = FaceTrackBinner(dur); feed(b)
        val id = b.idAt(0.2f, 0.45f, 1f)!!
        val s = b.series(id)
        assertEquals(1, s.otherTrackCount); assertNotNull(s.othersOff)
        val speech = BooleanArray((16000 * 20 + 511) / 512) { true }
        val p = TapProposal.propose(s.targetLipOn, s.othersOff, speech, dur)!!
        assertEquals(TapProposal.Strength.STRONG, p.strength)
        assertTrue(p.interval.startSec >= 0.5f && p.interval.endSec <= 9.0f)     // A's talking stretch only
    }

    @Test fun otherPersonsTalkingBlocksTheirOwnStretch() {
        val b = FaceTrackBinner(dur); feed(b)
        val idB = b.idAt(0.75f, 0.45f, 1f)!!
        val s = b.series(idB)
        val speech = BooleanArray((16000 * 20 + 511) / 512) { true }
        val p = TapProposal.propose(s.targetLipOn, s.othersOff, speech, dur)!!
        assertTrue(p.interval.startSec >= 10f && p.interval.endSec <= 19.0f)
    }

    @Test fun warmupIsNeverTreatedAsSilentOrTalking() {
        val b = FaceTrackBinner(dur); feed(b)
        val s = b.series(b.idAt(0.2f, 0.45f, 1f)!!)
        assertFalse(s.targetLipOn[0])                                            // first 0.7 s has no usable sample (unknown), never "on"
        val o = s.othersOff!!
        assertFalse(o[0])                                                         // other face is unknown during ITS warm-up, never "off"
    }

    @Test fun noOtherFaceMeansWeak() {
        val b = FaceTrackBinner(dur); feed(b, bVisible = false)
        val s = b.series(b.idAt(0.2f, 0.45f, 1f)!!)
        assertNull(s.othersOff); assertEquals(0, s.otherTrackCount)
        val speech = BooleanArray((16000 * 20 + 511) / 512) { true }
        assertEquals(TapProposal.Strength.WEAK, TapProposal.propose(s.targetLipOn, s.othersOff, speech, dur)!!.strength)
    }

    @Test(expected = IllegalArgumentException::class) fun outOfOrderFramesRejected() {
        val b = FaceTrackBinner(dur); b.add(1000, emptyList()); b.add(500, emptyList())
    }
    @Test(expected = IllegalArgumentException::class) fun unknownTrackRejected() { FaceTrackBinner(dur).series(99) }

    @Test fun facesAtListsBothBoxesAndFirstFaceTime() {
        val b = FaceTrackBinner(dur); feed(b, 3f)
        val f = b.facesAt(2f)
        assertEquals(2, f.size); assertNotEquals(f[0].first, f[1].first)
        assertEquals(0f, b.firstFaceSec()!!, 0.001f)
        assertTrue(FaceTrackBinner(dur).facesAt(2f).isEmpty()); assertNull(FaceTrackBinner(dur).firstFaceSec())
    }
}
