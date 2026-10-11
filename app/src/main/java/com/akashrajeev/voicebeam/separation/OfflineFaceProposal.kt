package com.akashrajeev.voicebeam.separation

import android.content.Context
import android.net.Uri
import android.util.Log
import com.akashrajeev.voicebeam.core.FaceProposalResult
import com.akashrajeev.voicebeam.core.FootageAnalysis
import com.akashrajeev.voicebeam.core.TapProposal
import com.akashrajeev.voicebeam.vision.OfflineFaceFeed
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Face-tap reference proposal for the offline video lane. Runs AFTER VAD by design:
 * TapProposal needs the speech mask, so this decodes the audio and computes the mask
 * with the same stages the import itself uses, then lets TapProposal pick the
 * reference interval for the face track the user tapped. The result feeds the
 * panel's proposal param; typed input still always wins there.
 */
object OfflineFaceProposal {
    /** Last proposal's evidence counts, shown on screen so a missing window can be explained. */
    @Volatile var lastDiag: String = ""

    /**
     * Proposal for [targetId] (from FaceTrackBinner.idAt): Found, NoWindow (everything was read, nothing qualifies) or Failed(reason) (decode/VAD/IO error).
     * The panel shows a different message for each; a failure is never reported as "no solo moment".
     */
    suspend fun compute(
        context: Context,
        uri: Uri,
        feed: OfflineFaceFeed.Result,
        targetId: Int,
        isCancelled: () -> Boolean = { false }
    ): FaceProposalResult = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "face-proposal-${System.nanoTime()}.mp4")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open selected video" }
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            val samples = VideoAudioDecoder.decode(tmp, isCancelled)
            val speech = OfflineSpeechMask.compute(context, samples, isCancelled)
            // Align the face bins with the decoded audio: container duration and audio
            // duration can drift by a fraction of a bin, and TapProposal requires one
            // lip flag per 0.5 s bin of the duration it is given.
            val durationSec = minOf(feed.durationSec, samples.size / 16000f)
            val bins = Math.ceil((durationSec / FootageAnalysis.BIN_SEC).toDouble()).toInt()
            val series = feed.binner.series(targetId)
            val lip = series.targetLipOn.copyOf(bins)          // grown bins: false = not talking (conservative)
            val others = series.othersOff?.copyOf(bins)        // grown bins: false = other not proven silent (conservative)
            val strict = TapProposal.propose(lip, others, speech, durationSec)
            // crowd clips: other faces' lips flicker, so no strict solo window exists. Fall back to the tapped lips + speech only (WEAK); the planner's cluster margin
            // and the face/voice cross-check still guard against the wrong person.
            val p = strict ?: TapProposal.propose(lip, null, speech, durationSec)
            val binLen = (FootageAnalysis.BIN_SEC * 16000).toInt()
            val spOk = BooleanArray(bins) { b -> val a = b * binLen; val e = minOf(samples.size, a + binLen); e - a >= binLen / 2 && com.akashrajeev.voicebeam.core.FootageWindows.speechCoverage(speech, a, e) >= TapProposal.MIN_BIN_SPEECH }
            lastDiag = "Evidence per 0.5 s: bins=$bins, tapped lips on=${lip.count { it }}, other faces silent=${others?.count { it } ?: -1} (-1 = no other face), speech=${spOk.count { it }}, " +
                "lips+speech=${(0 until bins).count { lip[it] && spOk[it] }}, strict window=${strict != null}, lips-only window=${p != null && strict == null}"
            if (p == null) FaceProposalResult.NoWindow else FaceProposalResult.Found(p)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w("OfflineFootage", "face proposal failed", t)
            FaceProposalResult.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            tmp.delete()
        }
    }
}
