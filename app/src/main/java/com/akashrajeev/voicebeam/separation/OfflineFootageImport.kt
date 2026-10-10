package com.akashrajeev.voicebeam.separation

import android.content.Context
import android.net.Uri
import com.akashrajeev.voicebeam.core.AbstainReason
import com.akashrajeev.voicebeam.core.FinalAudioGuardMath
import com.akashrajeev.voicebeam.core.TargetEvidence
import com.akashrajeev.voicebeam.core.FootageAnalysis
import com.akashrajeev.voicebeam.core.FootageRenderPipeline
import com.akashrajeev.voicebeam.core.FootageWindows
import com.akashrajeev.voicebeam.core.OfflineFootagePlanner
import com.akashrajeev.voicebeam.core.PlanResult
import com.akashrajeev.voicebeam.core.RenderOutcome
import com.akashrajeev.voicebeam.core.VoiceMatch
import com.akashrajeev.voicebeam.core.WavWriter
import com.akashrajeev.voicebeam.engine.SaveMode
import com.akashrajeev.voicebeam.ml.VoicePrint
import com.akashrajeev.voicebeam.record.MediaExporter
import com.akashrajeev.voicebeam.record.SessionMeta
import com.akashrajeev.voicebeam.record.SessionStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class FootageStage { COPY, DECODE, VAD, DENOISE, EMBED, EXTRACT, RENDER, MUX }

sealed class FootageResult {
    /** [routed] true = routed render shipped; false = the unchanged exp-10 path ran (see [note]). */
    class Done(val meta: SessionMeta, val routed: Boolean, val note: String) : FootageResult()
    /** Nothing was saved. The UI must ask the user to tap one moment where the target speaks alone. */
    class NeedsTap(val reason: AbstainReason) : FootageResult()
}

/**
 * Routed footage import (experiment-12). Does NOT modify OfflineVideoImport; it delegates to it, unchanged, whenever the
 * routed path cannot be trusted (single cluster, tap ambiguous, any gate failure). The original MP4 is always kept.
 * Rejected routed output is never exposed: the exp-10 path either produces its own guarded result or keeps the original.
 */
object OfflineFootageImport {
    /** Only the 16 kHz mono ceiling is honest here: the extractor is 8 kHz-class and no band split exists yet. */
    suspend fun run(
        context: Context, uri: Uri, tapStart: Double?, tapEnd: Double?,
        onProgress: (FootageStage, Float) -> Unit = { _, _ -> },
        /** Denoise a COPY of the audio before embedding windows. Render and guards always use the original. Failure falls back to raw embeddings (the stability gate and guard still apply). */
        denoiseFrontEnd: Boolean = true,
        /** Extractor-health relabel of TARGET_ONLY bins (TargetEvidence). OFF until eval's false-flag numbers land. */
        targetEvidence: Boolean = false
    ): FootageResult = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val cancelled = { job?.isActive == false }
        val store = SessionStore(context)
        val (id, dir) = store.newSessionDir()
        var keep = false
        try {
            require(dir.usableSpace > 550L * 1024 * 1024) { "Keep at least 550 MB free for offline import" }
            require((tapStart == null) == (tapEnd == null)) { "tap start/end must be given together" }
            val original = File(dir, "original.mp4")
            onProgress(FootageStage.COPY, 0f)
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open selected video" }
                original.outputStream().use { out ->
                    val b = ByteArray(65536); var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(b); if (n < 0) break
                        total += n; require(total <= 256L * 1024 * 1024) { "Choose a video under 256 MB" }
                        out.write(b, 0, n)
                    }
                }
            }
            onProgress(FootageStage.DECODE, 0f)
            val samples = VideoAudioDecoder.decode(original, cancelled)
            coroutineContext.ensureActive()
            val duration = samples.size / 16000f
            onProgress(FootageStage.VAD, 0f)
            val speech = OfflineSpeechMask.compute(context, samples, cancelled)
            coroutineContext.ensureActive()

            // ---- windows + plan
            var denoised: FloatArray? = null
            var embedsDenoised = false
            val diag = StringBuilder()   // why the routed path was not used; surfaced in the fallback text + logcat
            if (denoiseFrontEnd) {
                onProgress(FootageStage.DENOISE, 0f)
                denoised = try { OfflineDenoiser.denoise(context, samples, cancelled) { onProgress(FootageStage.DENOISE, it) } }
                           catch (e: kotlinx.coroutines.CancellationException) { throw e }
                           catch (e: Exception) { android.util.Log.w("OfflineFootage", "denoise front-end failed, using raw embeddings: " + e.message); diag.append("denoise failed: ${e.javaClass.simpleName} ${e.message}; "); null }
                           catch (e: OutOfMemoryError) { android.util.Log.w("OfflineFootage", "denoise front-end ran out of memory, using raw embeddings"); diag.append("denoise OOM; "); null }
                coroutineContext.ensureActive()
                embedsDenoised = denoised != null
                diag.append(if (embedsDenoised) "embeddings=denoised; " else "embeddings=raw; ")
            }
            onProgress(FootageStage.EMBED, 0f)
            val embed = VoicePrint(context.assets)
            val windows = try {
                FootageWindows.embedWindows(denoised ?: samples, 16000, embed = { embed.embed(it) }, isCancelled = cancelled,
                    // windows under 50% speech abstain: no embedding, no cluster membership, no reference candidacy
                    eligible = { a, b -> FootageWindows.speechCoverage(speech, a, b) >= FinalAudioGuardMath.MIN_SPEECH },
                    onProgress = { d, t -> onProgress(FootageStage.EMBED, d.toFloat() / t) })
            } finally { embed.release(); denoised = null }   // free the denoised copy; nothing downstream uses it
            coroutineContext.ensureActive()
            val tap = if (tapStart != null && tapEnd != null) FootageAnalysis.Interval(tapStart.toFloat(), tapEnd.toFloat()) else null
            val plan = if (windows == null || windows.isEmpty()) PlanResult.Abstain(AbstainReason.NO_CLUSTER)
                       else OfflineFootagePlanner.plan(windows, duration, tap = tap,
                            profile = if (embedsDenoised) OfflineFootagePlanner.MergeProfile.DENOISED else OfflineFootagePlanner.MergeProfile.RAW)

            if (plan is PlanResult.Abstain) {
                return@withContext fallbackOrAsk(context, uri, tapStart, tapEnd, plan.reason, dir, diag.toString() + "stage=plan")
            }
            plan as PlanResult.Plan

            // ---- reference guard (existing) on the planner's reference
            val reference = concat(samples, plan.reference)
            if (!OfflineQualityGuard.reference(context, reference))
                return@withContext fallbackOrAsk(context, uri, tapStart, tapEnd, AbstainReason.GUARD_REJECTED, dir, diag.toString() + "stage=reference-guard")

            // ---- extraction (existing, blocking <= 180 s, no cancel param)
            onProgress(FootageStage.EXTRACT, 0f)
            val raw = File(dir, "raw.wav"); WavWriter(raw, 16000).use { it.write(samples) }
            val clean = File(dir, "clean.wav")
            OfflineSpeakerBeam.extract(context, raw, reference, clean, cancelled)
            coroutineContext.ensureActive()
            val extracted = WavWriter.read(clean).first

            // ---- routed render + output gates (pure), THEN the selected-bin speaker guard on the FINAL rendered audio
            onProgress(FootageStage.RENDER, 0f)
            val labels = when (val ev = TargetEvidence.relabel(plan.labels.toTypedArray(), samples, extracted, speech, 16000, targetEvidence)) {
                is TargetEvidence.Result.Unchanged -> ev.labels
                is TargetEvidence.Result.Relabeled -> ev.labels
                is TargetEvidence.Result.Contradicted ->
                    return@withContext fallbackOrAsk(context, uri, tapStart, tapEnd, AbstainReason.EVIDENCE_CONTRADICTS_PLAN, dir, diag.toString() + "stage=evidence")
            }
            val outcome = FootageRenderPipeline.run(samples, extracted, null, labels, speech)
            if (outcome !is RenderOutcome.Rendered)
                return@withContext fallbackOrAsk(context, uri, tapStart, tapEnd, AbstainReason.GUARD_REJECTED, dir, diag.toString() + "stage=render-gates")
            if (!OfflineFootageGuard.output(context, samples, outcome.audio, reference, speech, labels))
                return@withContext fallbackOrAsk(context, uri, tapStart, tapEnd, AbstainReason.GUARD_REJECTED, dir, diag.toString() + "stage=output-guard")
            WavWriter(clean, 16000).use { it.write(outcome.audio) }
            coroutineContext.ensureActive()

            // ---- mux (existing proven path; 16 kHz mono ceiling)
            onProgress(FootageStage.MUX, 0f)
            val videoTmp = File(dir, "video.tmp.mp4")
            MediaExporter.muxVideoWithWav(original, clean, videoTmp)
            require(videoTmp.length() > 0 && videoTmp.renameTo(File(dir, "video.mp4"))) { "Cannot save video" }
            coroutineContext.ensureActive()

            val g = outcome.gates
            File(dir, "footage-report.txt").writeText(buildString {
                appendLine("Enhanced derivative. Original retained. On-device, offline. 16 kHz mono output.")
                appendLine("Target source: ${plan.source}; clusters: ${plan.clusterCount}; reference seconds: ${plan.reference.sumOf { (it.endSec - it.startSec).toDouble() }}")
                appendLine("Gates: passed. Mixture-RMS retention (TARGET_ONLY+OVERLAP bins, NOT verified target-stem retention): ${g.retentionDb} dB")
                appendLine("None-bin ratio: ${g.noneRatio}; worst band excess (0-4 kHz, 3 bands): ${g.worstBandExcessDb} dB")
                appendLine("Heuristic checks, not a quality certification. Isolation quality is not guaranteed.")
                appendLine("sha256 original.mp4: ${sha256(original)}")
                appendLine("sha256 video.mp4: ${sha256(File(dir, "video.mp4"))}")
            })
            val meta = SessionMeta(id, "Offline isolated video (routed)", System.currentTimeMillis(),
                samples.size * 1000L / 16000, SaveMode.AUDIO_VIDEO, "none", dir)
            store.writeMeta(meta, emptyList())
            keep = true
            FootageResult.Done(meta, true, "Routed render passed all gates")
        } catch (t: Throwable) {
            throw t
        } finally {
            if (!keep) dir.deleteRecursively()
        }
    }

    /** Abstain/gate-failure policy: tap given -> unchanged exp-10 path (own guards + "Original kept" fallback); no tap -> ask for a tap. */
    private suspend fun fallbackOrAsk(
        context: Context, uri: Uri, tapStart: Double?, tapEnd: Double?, reason: AbstainReason, dir: File, diag: String = ""
    ): FootageResult {
        dir.deleteRecursively()                        // drop this attempt's folder; exp-10 creates its own
        if (tapStart == null || tapEnd == null) return FootageResult.NeedsTap(reason)
        android.util.Log.w("OfflineFootage", "routed path not used: $reason ($diag)")
        val meta = OfflineVideoImport.run(context, uri, tapStart, tapEnd)
        try { File(meta.dir, "offline-fallback.txt").let { if (it.exists()) it.appendText(" [routed path not used: $reason; $diag]") } } catch (e: Exception) {}
        return FootageResult.Done(meta, false, "Routed path not used ($reason); exp-10 guarded path ran")
    }

    private fun concat(samples: FloatArray, ivs: List<FootageAnalysis.Interval>): FloatArray {
        val parts = ivs.map { samples.copyOfRange((it.startSec * 16000).toInt().coerceIn(0, samples.size), (it.endSec * 16000).toInt().coerceIn(0, samples.size)) }
        val out = FloatArray(parts.sumOf { it.size }); var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Selected-bin speaker-retention guard, run on the FINAL rendered audio (NEW; the old guard has no label selection).
 * Approximation: "mostly-selected" 3 s chunks, see FinalAudioGuardMath.
 */
object OfflineFootageGuard {
    fun output(
        context: Context, source: FloatArray, finalAudio: FloatArray, ref: FloatArray,
        speech: BooleanArray, labels: Array<FootageAnalysis.Seg>
    ): Boolean {
        val embed = VoicePrint(context.assets)
        try {
            val r = embed.embed(ref) ?: return false
            val srcScores = ArrayList<Float>(); val outScores = ArrayList<Float>(); val targetScores = ArrayList<Float>()
            for (a in source.indices step 48000) {
                val b = minOf(a + 48000, source.size)
                if (b - a < 16000) continue
                if (!FinalAudioGuardMath.chunkQualifies(labels, speech, a, b)) continue
                val s = embed.embed(source.copyOfRange(a, b)) ?: return false
                val y = embed.embed(finalAudio.copyOfRange(a, b)) ?: return false
                val yc = VoiceMatch.cosine(y, r)
                srcScores.add(VoiceMatch.cosine(s, r)); outScores.add(yc)
                if (FinalAudioGuardMath.targetChunkQualifies(labels, speech, a, b)) targetScores.add(yc)
            }
            if (outScores.isEmpty()) return false
            android.util.Log.i("OfflineFootageGuard", "sourceCosine=${srcScores.average()} outputCosine=${outScores.average()} chunks=${outScores.size} chunkScores=$outScores")
            // Routed renders get NO per-chunk floor: legitimately ducked other-speaker chunks score ~0 vs the target reference. Bad plans are caught by PLAN_UNSTABLE.
            // Target-region floor (see FinalAudioGuardMath): a wrongly ducked target fragment must not hide inside the mean.
            return FinalAudioGuardMath.pass(srcScores.average().toFloat(), outScores.average().toFloat(), outScores.size) &&
                !FinalAudioGuardMath.tooManyWeakTargetChunks(targetScores.toFloatArray())
        } finally { embed.release() }
    }
}
