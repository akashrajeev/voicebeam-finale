package com.akashrajeev.voicebeam.separation

import android.content.Context
import android.net.Uri
import com.akashrajeev.voicebeam.core.AbstainReason
import com.akashrajeev.voicebeam.core.BandLimiter
import com.akashrajeev.voicebeam.core.FaceVoiceCheck
import com.akashrajeev.voicebeam.core.WindowEmbedding
import com.akashrajeev.voicebeam.core.WindowSuggester
import com.akashrajeev.voicebeam.core.FaceVisibilityGate
import com.akashrajeev.voicebeam.core.LipSyncGate
import com.akashrajeev.voicebeam.core.FinalAudioGuardMath
import com.akashrajeev.voicebeam.core.SilenceMode
import com.akashrajeev.voicebeam.core.SilenceRender
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
        targetEvidence: Boolean = false,
        /** Non-target and empty bins: DUCK (default) = attenuate to a low floor (-26 dB); HARD_MUTE = true silence; FLOOR = old ducked behaviour. */
        silenceMode: SilenceMode = SilenceMode.DUCK,
        /** Render the target bins from the DENOISED audio (level-matched, gate-checked, falls back to the mixture with a report line). Needs the denoiser to have run. */
        denoiseRender: Boolean = true,
        /** Face-tap target: given the audio-plan labels, per-0.5 s "target face tracked" flags (FaceTrackBinner.visibleWithAliases). Null (typed reference) = audio-only labels. */
        faceVisibility: ((Array<FootageAnalysis.Seg>) -> BooleanArray?)? = null,
        /** Voice template from Listen's learn-locked-voice. Tried FIRST as the reference; if it cannot be matched confidently the tap (when given) is the fallback. */
        enrolled: FloatArray? = null,
        /** Face-tap target: per-0.5 s "target lips active" (FaceTrackBinner.series). Bins where the target's lips never move are muted (LipSyncGate). Null = off. */
        lipSeries: (() -> BooleanArray?)? = null
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
            } finally { embed.release() }   // the denoised copy is KEPT for the render (denoiseRender); it is freed when this call returns
            coroutineContext.ensureActive()
            val tap = if (tapStart != null && tapEnd != null) FootageAnalysis.Interval(tapStart.toFloat(), tapEnd.toFloat()) else null
            // Abstained short windows (e.g. a 1 s intro) are re-attached to the target by RAW embeddings (AbstainAttach, PROVISIONAL).
            // Only when clustering used denoised embeddings; a second VoicePrint is created lazily and only if a window needs it.
            var rawVp: VoicePrint? = null
            val rawWindows = windows
            val rawEmbedAt: (Int) -> FloatArray? = { i ->
                val w = rawWindows!![i]
                val a = (w.startSec * 16000).toInt().coerceIn(0, samples.size)
                val b = (w.endSec * 16000).toInt().coerceIn(a, samples.size)
                if (b - a < 16000) null else {
                    val vp = rawVp ?: VoicePrint(context.assets).also { rawVp = it }
                    vp.embed(samples.copyOfRange(a, b))
                }
            }
            val rawEmbedArg: ((Int) -> FloatArray?)? = if (embedsDenoised && rawWindows != null) rawEmbedAt else null
            val plan = try {
                if (windows == null || windows.isEmpty()) PlanResult.Abstain(AbstainReason.NO_CLUSTER)
                else {
                    val prof = if (embedsDenoised) OfflineFootagePlanner.MergeProfile.DENOISED else OfflineFootagePlanner.MergeProfile.RAW
                    val ep = if (enrolled != null) OfflineFootagePlanner.planEnrolled(windows, duration, enrolled, prof) else null
                    if (ep is PlanResult.Plan) { diag.append("reference=enrolled voice; "); ep }
                    else {
                        if (ep is PlanResult.Abstain) diag.append("enrolled voice not usable (${ep.reason}${if (ep.detail.isEmpty()) "" else ": " + ep.detail}); " + (if (tap != null) "falling back to the tap; " else ""))
                        OfflineFootagePlanner.plan(windows, duration, tap = tap, profile = prof, rawEmbed = rawEmbedArg)
                    }
                }
            } finally { try { rawVp?.release() } catch (_: Throwable) {} }

            if (plan is PlanResult.Abstain) {
                val hint = if (tap != null) suggestWindow(context, samples, windows, duration, embedsDenoised, cancelled) else ""
                return@withContext fallbackOrAsk(context, uri, tapStart, tapEnd, plan.reason, dir, diag.toString() + "stage=plan" + (if (plan.detail.isEmpty()) "" else "; " + plan.detail) + hint)
            }
            plan as PlanResult.Plan

            // ---- reference guard (existing) on the planner's reference
            val reference = concat(samples, plan.reference)
            val refReport = OfflineQualityGuard.referenceReport(context, reference)
            if (!refReport.trusted)
                return@withContext fallbackOrAsk(context, uri, tapStart, tapEnd, AbstainReason.GUARD_REJECTED, dir, diag.toString() + refReport.diagLine() + "stage=reference-guard" + suggestWindow(context, samples, windows, duration, embedsDenoised, cancelled))

            // ---- extraction (existing, blocking <= 180 s, no cancel param)
            onProgress(FootageStage.EXTRACT, 0f)
            val raw = File(dir, "raw.wav"); WavWriter(raw, 16000).use { it.write(samples) }
            val clean = File(dir, "clean.wav")
            OfflineSpeakerBeam.extract(context, raw, reference, clean, cancelled)
            coroutineContext.ensureActive()
            val extracted = WavWriter.read(clean).first

            // ---- routed render + output gates (pure), THEN the selected-bin speaker guard on the FINAL rendered audio
            onProgress(FootageStage.RENDER, 0f)
            val labels0 = when (val ev = TargetEvidence.relabel(plan.labels.toTypedArray(), samples, extracted, speech, 16000, targetEvidence)) {
                is TargetEvidence.Result.Unchanged -> ev.labels
                is TargetEvidence.Result.Relabeled -> ev.labels
                is TargetEvidence.Result.Contradicted ->
                    return@withContext fallbackOrAsk(context, uri, tapStart, tapEnd, AbstainReason.EVIDENCE_CONTRADICTS_PLAN, dir, diag.toString() + "stage=evidence")
            }
            // face gate: target face not tracked -> that audio is not the target (muted by the render); face tracked again -> the target label stays, voice returns
            var faceNote = "not used (no face tap)"
            val faceVis = try { faceVisibility?.invoke(labels0) } catch (e: Exception) { faceNote = "failed (${e.javaClass.simpleName}); audio-only labels"; null }
            val faceGate = FaceVisibilityGate.relabel(labels0, faceVis)
            val labelsFace: Array<FootageAnalysis.Seg> = when (faceGate) {
                is FaceVisibilityGate.Result.Relabeled -> { faceNote = "muted ${faceGate.mutedBins} of ${faceGate.targetOnlyBins} target bins (face not tracked)"; faceGate.labels }
                is FaceVisibilityGate.Result.Unchanged -> { if (faceVisibility != null && faceVis != null) faceNote = faceGate.reason; faceGate.labels }
            }
            // lip-sync gate: target lips not moving -> not the target talking -> muted. Distrusted (labels unchanged) when it would mute most of the target.
            val lipOn = try { lipSeries?.invoke() } catch (e: Exception) { faceNote += "; lip series failed (${e.javaClass.simpleName})"; null }
            // face/voice cross-check: tapped lips move but the chosen voice is absent -> voice and face are probably different people: abstain, never isolate the wrong person
            val xc = FaceVoiceCheck.crossCheck(labelsFace, lipOn)
            if (xc is FaceVoiceCheck.Check.Mismatch)
                return@withContext fallbackOrAsk(context, uri, tapStart, tapEnd, AbstainReason.EVIDENCE_CONTRADICTS_PLAN, dir, diag.toString() + "stage=face-voice-check; the tapped face's lips move but the locked voice is not there (" + xc.note + ")")
            val filled = FaceVoiceCheck.fillGaps(labelsFace, lipOn)
            faceNote += "; face/voice check: " + (xc as FaceVoiceCheck.Check.Ok).note + "; gap fill: ${filled.bins} bins"
            val lipGate = LipSyncGate.relabel(filled.labels, lipOn)
            val labels: Array<FootageAnalysis.Seg> = when (lipGate) {
                is LipSyncGate.Result.Relabeled -> { faceNote += "; lip gate muted ${lipGate.mutedBins} of ${lipGate.mutedBins + lipGate.keptBins} target bins (lips not moving)"; lipGate.labels }
                is LipSyncGate.Result.Unchanged -> { if (lipSeries != null && lipOn != null) faceNote += "; lip gate: ${lipGate.reason}"; lipGate.labels }
            }
            // render attempts, best first: denoised target bins (level-matched) -> denoised as-is -> the original mixture. Each attempt runs the SAME output gates and the
            // SAME speaker guard on the final audio; nothing is relaxed. A denoised attempt that fails either one drops to the next, and the report says why.
            val limiterNote = StringBuilder()
            val den = if (denoiseRender) denoised?.let { d0 ->
                val lr = BandLimiter.limit(samples, d0)
                limiterNote.append("band limiter scaled ${lr.limitedFrames} of ${lr.totalFrames} frames; ")
                lr.audio
            } else null
            // No makeup-gain attempt: gain applied before the output gates pushes speech frames over the band cap (measured: +1.8 dB -> 183 frames over, +2.2 dB -> 314),
            // so it could never pass; the speech-level gain runs AFTER the gates (Loudness) and already restores the level.
            val attempts = ArrayList<Pair<String, FloatArray?>>()
            if (den != null) attempts.add("denoised" to den)
            attempts.add((if (denoiseRender && den == null) "mixture (denoiser did not run)" else if (denoiseRender) "mixture (denoised render rejected: REASONS)" else "mixture (denoise render off)") to null)
            var outcome: RenderOutcome.Rendered? = null
            var renderNote = ""
            val rejected = ArrayList<String>()
            // Speaker guard for a denoised attempt runs in the denoised domain (denoised reference, denoised source baseline): the raw-domain comparison
            // loses 0.12-0.18 mean cosine for the SAME speaker (measured), which can trip the unchanged thresholds on noisy references.
            val denRef = if (den != null) concat(den, plan.reference) else null
            for ((name, d) in attempts) {
                coroutineContext.ensureActive()
                val o = FootageRenderPipeline.run(samples, extracted, d, labels, speech, silenceMode = silenceMode, normalizeLoudness = true)
                if (o !is RenderOutcome.Rendered) {
                    val why = (o as RenderOutcome.Rejected).gates.failures
                    android.util.Log.w("OfflineFootage", "render attempt '$name' rejected by gates: $why")
                    rejected.add("$name: gates ${why.joinToString(", ")}"); continue
                }
                val detail = StringBuilder()
                val guardOk = if (d != null && denRef != null) OfflineFootageGuard.output(context, d, o.audio, denRef, speech, labels, detail)
                              else OfflineFootageGuard.output(context, samples, o.audio, reference, speech, labels, detail)
                if (!guardOk) {
                    android.util.Log.w("OfflineFootage", "render attempt '$name' rejected by speaker guard: $detail")
                    rejected.add("$name: speaker guard $detail"); continue
                }
                outcome = o; renderNote = name; break
            }
            denoised = null
            val chosen = outcome ?: return@withContext fallbackOrAsk(context, uri, tapStart, tapEnd, AbstainReason.GUARD_REJECTED, dir, diag.toString() + "stage=render-gates-or-output-guard")
            WavWriter(clean, 16000).use { it.write(chosen.audio) }
            coroutineContext.ensureActive()

            // ---- mux (existing proven path; 16 kHz mono ceiling)
            onProgress(FootageStage.MUX, 0f)
            val videoTmp = File(dir, "video.tmp.mp4")
            MediaExporter.muxVideoWithWav(original, clean, videoTmp)
            require(videoTmp.length() > 0 && videoTmp.renameTo(File(dir, "video.mp4"))) { "Cannot save video" }
            coroutineContext.ensureActive()

            val g = chosen.gates
            File(dir, "footage-report.txt").writeText(buildString {
                appendLine("Enhanced derivative. Original retained. On-device, offline. 16 kHz mono output.")
                appendLine("Target source: ${plan.source}; clusters: ${plan.clusterCount}; reference seconds: ${plan.reference.sumOf { (it.endSec - it.startSec).toDouble() }}")
                appendLine("Gates: passed. Mixture-RMS retention (TARGET_ONLY+OVERLAP bins, NOT verified target-stem retention): ${g.retentionDb} dB")
                appendLine("None-bin ratio: ${g.noneRatio}; worst band excess (0-4 kHz, 3 bands): ${g.worstBandExcessDb} dB")
                appendLine("Embeddings: ${if (embedsDenoised) "denoised (pin 0.41)" else "raw (pin 0.45)"}")
                appendLine("Final 0.5 s bin labels (T=target only, O=other only, V=overlap, N=none): " + labels.joinToString("") { when (it) { FootageAnalysis.Seg.TARGET_ONLY -> "T"; FootageAnalysis.Seg.OTHER_ONLY -> "O"; FootageAnalysis.Seg.OVERLAP -> "V"; FootageAnalysis.Seg.NONE -> "N" } })
                appendLine("Silence mode: ${when (silenceMode) { SilenceMode.HARD_MUTE -> "HARD_MUTE (non-target and empty bins are silent)"; SilenceMode.DUCK -> "DUCK (non-target speakers/lips-stopped bins at -26 dB, not silent)"; else -> "FLOOR (non-target ducked)" }}")
                appendLine("Noise suppression: ${limiterNote}${renderNote.replace("REASONS", rejected.joinToString("; ").ifEmpty { "n/a" })}")
                appendLine("Mute edges moved to speech pauses: ${chosen.snappedEdges}; speech-level gain: ${"%.1f".format(chosen.gainDb)} dB")
                appendLine("Face gate: $faceNote")
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

    /** "; try a-b s ..." for a refused tap, from the SAME planner and reference guard (no thresholds changed). Never throws; empty if it cannot run. */
    private fun suggestWindow(
        context: Context, samples: FloatArray, windows: List<WindowEmbedding>?, duration: Float, denoisedProfile: Boolean, cancelled: () -> Boolean
    ): String {
        if (windows == null || windows.isEmpty()) return ""
        return try {
            val found = WindowSuggester.suggest(
                windows, duration,
                if (denoisedProfile) OfflineFootagePlanner.MergeProfile.DENOISED else OfflineFootagePlanner.MergeProfile.RAW,
                referencePasses = { a, b ->
                    val i0 = (a * 16000).toInt().coerceIn(0, samples.size); val i1 = (b * 16000).toInt().coerceIn(i0, samples.size)
                    OfflineQualityGuard.referenceReport(context, samples.copyOfRange(i0, i1)).trusted
                },
                isCancelled = cancelled)
            "; " + WindowSuggester.note(found)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { android.util.Log.w("OfflineFootage", "window suggestion failed: " + e.message); "" }
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
        speech: BooleanArray, labels: Array<FootageAnalysis.Seg>, detail: StringBuilder? = null
    ): Boolean {
        val embed = VoicePrint(context.assets)
        try {
            val r = embed.embed(ref)
            if (r == null) { detail?.append("reference could not be embedded"); return false }
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
            if (outScores.isEmpty()) { detail?.append("no chunk qualified"); return false }
            android.util.Log.i("OfflineFootageGuard", "sourceCosine=${srcScores.average()} outputCosine=${outScores.average()} chunks=${outScores.size} chunkScores=$outScores")
            detail?.append("(source cos %.2f, output cos %.2f, %d chunks, %d weak target chunks)".format(srcScores.average(), outScores.average(), outScores.size, targetScores.count { !it.isFinite() || it < FinalAudioGuardMath.TARGET_CHUNK_FLOOR }))
            // Routed renders get NO per-chunk floor: legitimately ducked other-speaker chunks score ~0 vs the target reference. Bad plans are caught by PLAN_UNSTABLE.
            // Target-region floor (see FinalAudioGuardMath): a wrongly ducked target fragment must not hide inside the mean.
            return FinalAudioGuardMath.pass(srcScores.average().toFloat(), outScores.average().toFloat(), outScores.size) &&
                !FinalAudioGuardMath.tooManyWeakTargetChunks(targetScores.toFloatArray())
        } finally { embed.release() }
    }
}
