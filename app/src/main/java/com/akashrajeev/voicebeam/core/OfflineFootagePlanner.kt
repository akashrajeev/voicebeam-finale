package com.akashrajeev.voicebeam.core

enum class AbstainReason { NO_CLUSTER, SINGLE_CLUSTER, TAP_REQUIRED, TAP_NOT_IN_CLUSTER, REFERENCE_TOO_SHORT, LIP_AMBIGUOUS, GUARD_REJECTED, PLAN_UNSTABLE }
enum class TargetSource { TAP, FACE }

sealed class PlanResult {
    class Plan(
        val target: Int, val clusterCount: Int, val source: TargetSource,
        val reference: List<FootageAnalysis.Interval>,
        val labels: List<FootageAnalysis.Seg>, val purity: List<Float>
    ) : PlanResult()
    /** Abstain never means "return degraded output": the caller falls back to the existing extractor path or asks for a tap. */
    class Abstain(val reason: AbstainReason) : PlanResult()
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

    fun plan(
        ws: List<WindowEmbedding>, durationSec: Float,
        tap: FootageAnalysis.Interval? = null,
        lipBinned: FloatArray? = null, otherLipBinned: List<FloatArray> = emptyList(),
        lipOnThreshold: Float = 0.5f, othersOffThreshold: Float = 0.3f
    ): PlanResult {
        // Tap validation runs BEFORE any abstain so a malformed tap is always a caller error, never masked by NO_CLUSTER.
        if (tap != null) require(tap.startSec.isFinite() && tap.endSec.isFinite() && tap.startSec >= 0f && tap.endSec > tap.startSec && tap.endSec <= durationSec) { "tap outside audio" }
        val ca = ClusteredAnalysis.of(ws, durationSec)
        if (ca.clusterCount == 0) return PlanResult.Abstain(AbstainReason.NO_CLUSTER)

        if (tap != null) {
            if (tap.endSec - tap.startSec < MIN_TAP_SEC) return PlanResult.Abstain(AbstainReason.REFERENCE_TOO_SHORT)
            val clusters = ca.windowClusters()
            val inside = ws.indices.filter { ws[it].startSec >= tap.startSec && ws[it].endSec <= tap.endSec }
            val valid = inside.filter { clusters[it] >= 0 }
            if (inside.size < MIN_TAP_WINDOWS || valid.size < MIN_TAP_WINDOWS) return PlanResult.Abstain(AbstainReason.TAP_NOT_IN_CLUSTER)
            val counts = valid.groupingBy { clusters[it] }.eachCount()
            val ranked = counts.entries.sortedByDescending { it.value }
            val best = ranked[0]
            val runnerUp = if (ranked.size > 1) ranked[1].value else 0
            if ((best.value - runnerUp).toFloat() / inside.size < MIN_TAP_MARGIN) return PlanResult.Abstain(AbstainReason.TAP_NOT_IN_CLUSTER)
            if (best.value.toFloat() / inside.size < MIN_TAP_SHARE) return PlanResult.Abstain(AbstainReason.TAP_NOT_IN_CLUSTER)
            // Routed render needs other speakers to separate from; one cluster gives no such evidence.
            if (ca.clusterCount < 2) return PlanResult.Abstain(AbstainReason.SINGLE_CLUSTER)
            val base = roles(ws, durationSec, tap, FootageAnalysis.DEFAULT_MERGE_COS)
            for (pm in PERTURB_MERGE_COS) {
                val r = roles(ws, durationSec, tap, pm)
                val agree = if (base == null || r == null) 0f else base.indices.count { base[it] == r[it] }.toFloat() / base.size
                if (agree < MIN_PLAN_STABILITY) return PlanResult.Abstain(AbstainReason.PLAN_UNSTABLE)
            }
            return finish(ca, best.key, TargetSource.TAP, listOf(tap))
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
