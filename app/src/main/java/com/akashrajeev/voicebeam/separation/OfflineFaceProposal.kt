package com.akashrajeev.voicebeam.separation

import android.content.Context
import android.net.Uri
import android.util.Log
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

    /**
     * Proposal for [targetId] (from FaceTrackBinner.idAt), or null when nothing
     * qualifies or any stage fails - the panel then asks for a typed moment.
     */
    suspend fun compute(
        context: Context,
        uri: Uri,
        feed: OfflineFaceFeed.Result,
        targetId: Int,
        isCancelled: () -> Boolean = { false }
    ): TapProposal.Proposal? = withContext(Dispatchers.IO) {
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
            TapProposal.propose(lip, others, speech, durationSec)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w("OfflineFootage", "face proposal failed", t)
            null
        } finally {
            tmp.delete()
        }
    }
}
