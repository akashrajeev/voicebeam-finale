package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TargetEvidenceTest {
    private val sr = 16000
    private val bin = sr / 2
    private fun tone(n: Int, amp: Float) = FloatArray(n) { (amp * sin(2 * PI * 300 * it / sr)).toFloat() }
    private fun labels(n: Int, vararg over: Pair<IntRange, Seg>): Array<Seg> {
        val l = Array(n) { Seg.TARGET_ONLY }
        for ((r, s) in over) for (i in r) l[i] = s
        return l
    }
    /** source tone (amp .1, about -23 dB rms) everywhere; extracted = health * source in every bin except overrides. */
    private fun clip(bins: Int, healths: (Int) -> Float): Pair<FloatArray, FloatArray> {
        val src = tone(bins * bin, 0.1f)
        val ext = FloatArray(src.size) { src[it] * healths(it / bin) }
        return src to ext
    }

    private fun rel(l: Array<Seg>, s: FloatArray, e: FloatArray, enabled: Boolean = false, speechOn: Boolean = true) =
        TargetEvidence.relabel(l, s, e, BooleanArray((maxOf(s.size, 1) + 511) / 512) { speechOn }, sr, enabled)
    @Test fun loudNonSpeechIsNeverEvidence() {
        val (s, e) = clip(12) { 0.1f }                                   // loud (-23 dB) and low health everywhere, but the VAD says not speech
        assertTrue(rel(labels(12), s, e, enabled = true, speechOn = false) is TargetEvidence.Result.Unchanged)
        assertTrue(rel(labels(12), s, e, enabled = true, speechOn = true) is TargetEvidence.Result.Contradicted)   // same audio with speech: flagged (and contradicted)
    }
    @Test(expected = IllegalArgumentException::class) fun speechMaskMustCoverAudio() {
        val (s, e) = clip(4) { 0.5f }
        TargetEvidence.relabel(labels(4), s, e, BooleanArray(3), sr, true)
    }
    @Test fun offByDefaultIsUnchanged() {
        val (s, e) = clip(10) { 0.1f }
        assertTrue(rel(labels(10), s, e) is TargetEvidence.Result.Unchanged)
    }
    @Test fun healthyTargetIsUntouched() {
        val (s, e) = clip(10) { 0.66f }
        assertTrue(rel(labels(10), s, e, enabled = true) is TargetEvidence.Result.Unchanged)
    }
    @Test fun sustainedLowHealthRunIsRelabelled() {
        val (s, e) = clip(12) { b -> if (b in 6..8) 0.4f else 0.66f }      // 3 bins = 25% of target bins
        val r = rel(labels(12), s, e, enabled = true) as TargetEvidence.Result.Relabeled
        assertEquals(3, r.flaggedBins)
        for (b in 6..8) assertEquals(Seg.OTHER_ONLY, r.labels[b])
        assertEquals(Seg.TARGET_ONLY, r.labels[5]); assertEquals(Seg.TARGET_ONLY, r.labels[9])
    }
    @Test fun shortRunsAreNotRelabelled() {
        val (s, e) = clip(12) { b -> if (b == 4 || b == 5 || b == 8) 0.3f else 0.66f }   // runs of 2 and 1
        assertTrue(rel(labels(12), s, e, enabled = true) is TargetEvidence.Result.Unchanged)
    }
    @Test fun quietBinsProvideNoEvidence() {
        // near-silence (about -83 dB, under the -60 dB ABS floor): health is meaningless there, never counts even when the whole clip is that quiet
        val src = tone(12 * bin, 0.0001f); val ext = FloatArray(src.size) { src[it] * 0.1f }
        assertTrue(rel(labels(12), src, ext, enabled = true) is TargetEvidence.Result.Unchanged)
    }
    @Test fun onlyTargetOnlyBinsAreConsidered() {
        val (s, e) = clip(12) { 0.2f }
        val l = labels(12, 0..11 to Seg.OTHER_ONLY)
        assertTrue(rel(l, s, e, enabled = true) is TargetEvidence.Result.Unchanged)
    }
    @Test fun contradictedWhenShareTooLarge() {
        val (s, e) = clip(10) { b -> if (b in 2..7) 0.3f else 0.66f }       // 6 of 10 = 60% > 40%
        val r = rel(labels(10), s, e, enabled = true)
        assertTrue(r is TargetEvidence.Result.Contradicted)
        assertEquals(6, (r as TargetEvidence.Result.Contradicted).flaggedBins)
    }
    @Test fun shareExactlyAtLimitIsAllowed() {
        val (s, e) = clip(10) { b -> if (b in 3..6) 0.3f else 0.66f }       // 4 of 10 = 40% exactly
        assertTrue(rel(labels(10), s, e, enabled = true) is TargetEvidence.Result.Relabeled)
    }
    @Test fun thresholdBoundaryHealthPointFiveIsNotEvidence() {
        val (s, e) = clip(10) { 0.5f }
        assertTrue(rel(labels(10), s, e, enabled = true) is TargetEvidence.Result.Unchanged)
    }
    @Test fun quietClipStillWorksBecauseLevelGateIsRelative() {
        val src = tone(12 * bin, 0.03f)                                  // about -33 dB rms: the old absolute -26 dB gate disabled the rule here
        val ext = FloatArray(src.size) { src[it] * (if (it / bin in 6..8) 0.3f else 0.66f) }
        assertTrue(rel(labels(12), src, ext, enabled = true) is TargetEvidence.Result.Relabeled)
    }
    @Test fun binFarBelowClipSpeechLevelIsNotEvidence() {
        val src = FloatArray(12 * bin) { (sin(2 * PI * 300 * it / sr) * (if (it / bin in 6..8) 0.005 else 0.1)).toFloat() }   // 3 bins 26 dB under the rest
        val ext = FloatArray(src.size) { src[it] * (if (it / bin in 6..8) 0.1f else 0.66f) }
        assertTrue(rel(labels(12), src, ext, enabled = true) is TargetEvidence.Result.Unchanged)
    }
    @Test fun inputIsNeverMutated() {
        val (s, e) = clip(12) { b -> if (b in 6..8) 0.4f else 0.66f }
        val l = labels(12); rel(l, s, e, enabled = true)
        assertTrue(l.all { it == Seg.TARGET_ONLY })
    }
    @Test(expected = IllegalArgumentException::class) fun lengthMismatchRejected() {
        rel(labels(4), FloatArray(100), FloatArray(99), enabled = true)
    }
}
