package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FootageAnalysis.Seg
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pause-snapped mute boundaries (pure JVM, 16 kHz mono, VAD frames of [FRAME] samples).
 *
 * The planner labels 0.5 s bins, so a hard mute starts and stops on a 0.5 s grid; when the edge falls inside continuous speech the last or first word of a
 * turn can be clipped or a bit of the other speaker kept. For every boundary between a KEPT bin (TARGET_ONLY / OVERLAP) and an OTHER_ONLY bin that lies inside
 * speech, the boundary moves to the centre of the nearest VAD pause (at least [MIN_PAUSE_FRAMES] frames of non-speech) within +-[MAX_SHIFT_SEC]. Edges that are already
 * in a pause stay; edges with no pause nearby stay on the grid (unchanged behaviour). NONE bins never move (they are silence by label and a gate watches them).
 * Moving the boundary INTO an OTHER_ONLY bin keeps audio the plan called other, so it is limited to the first pause (a speaker's own breath/gap) and 0.2 s;
 * PROVISIONAL numbers. Shifts are < 0.25 s so two edges can never cross inside one 0.5 s bin.
 */
object BoundarySnap {
    const val FRAME = 512
    const val MIN_PAUSE_FRAMES = 3
    const val MAX_SHIFT_SEC = 0.2f

    class Mask(val keep: BooleanArray, val origKeep: BooleanArray, val movedEdges: Int, val extendEdges: IntArray)
    /** Around an extended edge the routed render's own crossfade has partly ducked the kept side; use the plain base signal this far either side. */
    const val SEAM_SEC = 0.13f

    private fun kept(s: Seg) = s == Seg.TARGET_ONLY || s == Seg.OVERLAP

    fun mask(labels: Array<Seg>, speech: BooleanArray, nSamples: Int, sampleRate: Int = 16000): Mask {
        val binLen = (FootageAnalysis.BIN_SEC * sampleRate).toInt()
        require(binLen > 0 && labels.isNotEmpty() && labels.size * binLen >= nSamples) { "labels do not cover the audio" }
        require(speech.size.toLong() * FRAME >= nSamples - FRAME) { "speech flags do not cover the audio" }
        val orig = BooleanArray(nSamples) { kept(labels[min(labels.size - 1, it / binLen)]) }
        val keep = orig.copyOf()
        val maxShift = (MAX_SHIFT_SEC * sampleRate).toInt()
        fun isSpeech(frame: Int) = frame in speech.indices && speech[frame]
        // pause centres (sample index) as runs of >= MIN_PAUSE_FRAMES non-speech frames
        val centres = ArrayList<Int>()
        var f = 0
        while (f < speech.size) {
            if (!speech[f]) { var e = f; while (e < speech.size && !speech[e]) e++; if (e - f >= MIN_PAUSE_FRAMES) centres.add(((f + e) / 2) * FRAME); f = e } else f++
        }
        var moved = 0
        val ext = ArrayList<Int>()
        for (b in 1 until labels.size) {
            val l = labels[b - 1]; val r = labels[b]
            val keptLeft = kept(l); val keptRight = kept(r)
            if (keptLeft == keptRight) continue
            if ((keptLeft && r != Seg.OTHER_ONLY) || (keptRight && l != Seg.OTHER_ONLY)) continue      // NONE edges never move
            val e = b * binLen
            if (e <= 0 || e >= nSamples) continue
            val inSpeech = isSpeech((e - 1) / FRAME) && isSpeech(e / FRAME)
            if (!inSpeech) continue                                                                    // already in a pause: leave it
            val p = centres.filter { abs(it - e) <= maxShift }.minByOrNull { abs(it - e) } ?: continue
            if (p == e) continue
            moved++
            val lo = min(e, p); val hi = min(nSamples, max(e, p))
            // kept side is left: p > e extends kept, p < e shrinks it. kept side is right: mirrored.
            val extend = if (keptLeft) p > e else p < e
            for (i in lo until hi) keep[i] = extend
            if (extend) ext.add(e)
        }
        return Mask(keep, orig, moved, ext.toIntArray())
    }

    /**
     * Out = [primary] on samples kept in the original grid (except within [SEAM_SEC] of an extended edge), [extension] (denoised or source, already level-matched) on samples kept only because of a snap, 0 where muted,
     * with [fadeSec] linear fades in the kept samples next to every muted sample.
     */
    fun compose(primary: FloatArray, extension: FloatArray, m: Mask, sampleRate: Int = 16000, fadeSec: Float = 0.02f): FloatArray {
        val n = primary.size
        require(extension.size == n && m.keep.size == n) { "length mismatch" }
        require(fadeSec.isFinite() && fadeSec >= 0.01f && fadeSec <= 0.25f) { "fade must be 10-250 ms" }
        val fade = max(1, (fadeSec * sampleRate).toInt())
        // distance to the nearest muted sample, capped at fade (two sweeps)
        val dist = IntArray(n) { if (m.keep[it]) fade else 0 }
        for (i in 1 until n) dist[i] = min(dist[i], dist[i - 1] + 1)
        for (i in n - 2 downTo 0) dist[i] = min(dist[i], dist[i + 1] + 1)
        val seam = (SEAM_SEC * sampleRate).toInt()
        val useExt = BooleanArray(n)
        for (e in m.extendEdges) for (i in max(0, e - seam) until min(n, e + seam)) useExt[i] = true
        return FloatArray(n) { i ->
            if (!m.keep[i]) 0f
            else (if (m.origKeep[i] && !useExt[i]) primary[i] else extension[i]) * min(1f, dist[i].toFloat() / fade)
        }
    }
}
