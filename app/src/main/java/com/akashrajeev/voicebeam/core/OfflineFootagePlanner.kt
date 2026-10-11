package com.akashrajeev.voicebeam.core

enum class AbstainReason { NO_CLUSTER, SINGLE_CLUSTER, TAP_REQUIRED, TAP_NOT_IN_CLUSTER, REFERENCE_TOO_SHORT, LIP_AMBIGUOUS, GUARD_REJECTED, PLAN_UNSTABLE, EVIDENCE_CONTRADICTS_PLAN }
enum class TargetSource { TAP, FACE }

sealed class PlanResult {
    class Plan(
        val target: Int, val clusterCount: Int, val source: TargetSource,
        val reference: List<FootageAnalysis.Interval>,
        val labels: List<FootageAnalysis.Seg>, val purity: List<Float>
    ) : PlanResult()
    /** Abstain never means "return degraded output": the caller falls back to the existing extractor path or asks for a tap. */
    class Abstain(val reason: AbstainReason, val detail: String = "") : PlanResult()
}

/** Pure decision logic: windows (+ optional tap interval, lip series) -> Plan or Abstain. No audio, no ORT, no Android. */
object OfflineFootagePlanner {
    const val MIN_TAP_SEC = 2f
    const val MIN_TAP_WINDOWS = 2
    const val MIN_TAP_SHARE = 0.5f
    /** Winner must beat the runner-up by at least this share of the windows inside the tap; ties or near-ties abstain. */
    const val MIN_TAP_MARGIN = 0.2f
    /**
     * Plan-stability gate: a CONSISTENCY (threshold-sensitivity) check only, not proof of correct identity. A consistently wrong identity can be stable across mergeCos values; FinalAudioGuard remains the real backstop.
     * The window roles (target / other / abstained) must agree with the roles at mergeCos 0.40, 0.50 and 0.55 (pin is 0.45: one value below, two above; all levels eval-swept) for at least this
     * fraction of windows. Calibration (5 embedding fixtures): good plans min 0.93/1.00/0.94, mislabelled plans 0.82/0.73. PROVISIONAL, tiny sample.
     */
    const val MIN_PLAN_STABILITY = 0.88f
    private val PERTURB_MERGE_COS = floatArrayOf(0.40f, 0.50f, 0.55f)
    /**
     * The pin is a property of the embedding pipeline. RAW embeddings: pin 0.45, set {0.40,0.50,0.55} (the proven behaviour, unchanged default).
     * DENOISED embeddings have different geometry: pin 0.41, set {0.39,0.43} (eval's full-chain table; PROVISIONAL, small sample).
     */
    const val DENOISED_MERGE_COS = 0.41f
    private val DENOISED_PERTURB_MERGE_COS = floatArrayOf(0.39f, 0.43f)
    data class MergeProfile(val pin: Float, val perturb: FloatArray) {
        companion object { val RAW = MergeProfile(FootageAnalysis.DEFAULT_MERGE_COS, PERTURB_MERGE_COS); val DENOISED = MergeProfile(DENOISED_MERGE_COS, DENOISED_PERTURB_MERGE_COS) }
    }

    fun plan(
        ws: List<WindowEmbedding>, durationSec: Float,
        tap: FootageAnalysis.Interval? = null,
        lipBinned: FloatArray? = null, otherLipBinned: List<FloatArray> = emptyList(),
        lipOnThreshold: Float = 0.5f, othersOffThreshold: Float = 0.3f,
        profile: MergeProfile = MergeProfile.RAW,
        /** Tap path only: raw-audio embedding per WINDOW INDEX, used to re-attach abstained windows to the target (AbstainAttach, PROVISIONAL). Null = off. */
        rawEmbed: ((Int) -> FloatArray?)? = null
    ): PlanResult {
        // Tap validation runs BEFORE any abstain so a malformed tap is always a caller error, never masked by NO_CLUSTER.
        if (tap != null) require(tap.startSec.isFinite() && tap.endSec.isFinite() && tap.startSec >= 0f && tap.endSec > tap.startSec && tap.endSec <= durationSec) { "tap outside audio" }
        val ca = ClusteredAnalysis.of(ws, durationSec, profile.pin)
        if (ca.clusterCount == 0) return PlanResult.Abstain(AbstainReason.NO_CLUSTER)

        if (tap != null) {
            if (tap.endSec - tap.startSec < MIN_TAP_SEC) return PlanResult.Abstain(AbstainReason.REFERENCE_TOO_SHORT)
            val clusters = ca.windowClusters()
            val inside = ws.indices.filter { ws[it].startSec >= tap.startSec && ws[it].endSec <= tap.endSec }
            val valid = inside.filter { clusters[it] >= 0 }
            val tapDesc = "tap %.1f-%.1fs, clusters=${ca.clusterCount}, tap windows=${inside.size}, in a cluster=${valid.size}".format(tap.startSec, tap.endSec)
            if (inside.size < MIN_TAP_WINDOWS || valid.size < MIN_TAP_WINDOWS) return PlanResult.Abstain(AbstainReason.TAP_NOT_IN_CLUSTER, "$tapDesc; check=too few tap windows in any cluster (need $MIN_TAP_WINDOWS)")
            val counts = valid.groupingBy { clusters[it] }.eachCount()
            val ranked = counts.entries.sortedByDescending { it.value }
            val best = ranked[0]
            val runnerUp = if (ranked.size > 1) ranked[1].value else 0
            val margin = (best.value - runnerUp).toFloat() / inside.size
            val share = best.value.toFloat() / inside.size
            val tapStats = "$tapDesc, best cluster=${best.value} windows, runner-up=$runnerUp, share=%.2f, margin=%.2f".format(share, margin)
            if (margin < MIN_TAP_MARGIN) return PlanResult.Abstain(AbstainReason.TAP_NOT_IN_CLUSTER, "$tapStats; check=margin ${"%.2f".format(margin)} < $MIN_TAP_MARGIN (tap spans two clusters)")
            if (share < MIN_TAP_SHARE) return PlanResult.Abstain(AbstainReason.TAP_NOT_IN_CLUSTER, "$tapStats; check=share ${"%.2f".format(share)} < $MIN_TAP_SHARE")
            // Routed render needs other speakers to separate from; one cluster gives no such evidence.
            if (ca.clusterCount < 2) return PlanResult.Abstain(AbstainReason.SINGLE_CLUSTER, "clusters=${ca.clusterCount}")
            val base = roles(ws, durationSec, tap, profile.pin)
            for (pm in profile.perturb) {
                val r = roles(ws, durationSec, tap, pm)
                val agree = if (base == null || r == null) 0f else base.indices.count { base[it] == r[it] }.toFloat() / base.size
                if (agree < MIN_PLAN_STABILITY) return PlanResult.Abstain(AbstainReason.PLAN_UNSTABLE, "role agreement %.2f < $MIN_PLAN_STABILITY at merge cosine $pm".format(agree))
            }
            return finish(if (rawEmbed != null) ca.withAbstainedAttached(best.key, rawEmbed) else ca, best.key, TargetSource.TAP, listOf(tap))
        }

        if (lipBinned == null) return PlanResult.Abstain(AbstainReason.TAP_REQUIRED)
        val asg = ca.assignFace(lipBinned) ?: return PlanResult.Abstain(AbstainReason.LIP_AMBIGUOUS)
        val ta = ca.forTarget(asg.cluster)
        val othersOff = FootageWindows.othersOff(otherLipBinned, othersOffThreshold)
        val ref = ta.autoReference(lipOn = FootageWindows.lipOn(lipBinned, lipOnThreshold), othersOffLip = othersOff)
        if (ref == null) return PlanResult.Abstain(if (ca.clusterCount < 2 && othersOff == null) AbstainReason.SINGLE_CLUSTER else AbstainReason.REFERENCE_TOO_SHORT)
        return PlanResult.Plan(asg.cluster, ca.clusterCount, TargetSource.FACE, ref, ta.labels, ta.purity)
    }

    /** Window roles under a perturbed clustering: 0 = abstained, 1 = target cluster (chosen from the same tap), 2 = other. Null if no safe target. */
    private fun roles(ws: List<WindowEmbedding>, durationSec: Float, tap: FootageAnalysis.Interval, mergeCos: Float): IntArray? {
        val ca = ClusteredAnalysis.of(ws, durationSec, mergeCos)
        if (ca.clusterCount < 2) return null
        val clusters = ca.windowClusters()
        val inside = ws.indices.filter { ws[it].startSec >= tap.startSec && ws[it].endSec <= tap.endSec }
        val valid = inside.filter { clusters[it] >= 0 }
        if (inside.size < MIN_TAP_WINDOWS || valid.size < MIN_TAP_WINDOWS) return null
        val ranked = valid.groupingBy { clusters[it] }.eachCount().entries.sortedByDescending { it.value }
        val runnerUp = if (ranked.size > 1) ranked[1].value else 0
        if ((ranked[0].value - runnerUp).toFloat() / inside.size < MIN_TAP_MARGIN) return null
        if (ranked[0].value.toFloat() / inside.size < MIN_TAP_SHARE) return null
        val target = ranked[0].key
        return IntArray(ws.size) { if (clusters[it] < 0) 0 else if (clusters[it] == target) 1 else 2 }
    }

    private fun finish(ca: ClusteredAnalysis, target: Int, src: TargetSource, ref: List<FootageAnalysis.Interval>): PlanResult {
        val ta = ca.forTarget(target)
        return PlanResult.Plan(target, ca.clusterCount, src, ref, ta.labels, ta.purity)
    }
}
