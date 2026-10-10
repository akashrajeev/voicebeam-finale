package com.akashrajeev.voicebeam.core

enum class AbstainReason { NO_CLUSTER, SINGLE_CLUSTER, TAP_REQUIRED, TAP_NOT_IN_CLUSTER, REFERENCE_TOO_SHORT, LIP_AMBIGUOUS }
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

    fun plan(
        ws: List<WindowEmbedding>, durationSec: Float,
        tap: FootageAnalysis.Interval? = null,
        lipBinned: FloatArray? = null, otherLipBinned: List<FloatArray> = emptyList(),
        lipOnThreshold: Float = 0.5f, othersOffThreshold: Float = 0.3f
    ): PlanResult {
        val ca = ClusteredAnalysis.of(ws, durationSec)
        if (ca.clusterCount == 0) return PlanResult.Abstain(AbstainReason.NO_CLUSTER)

        if (tap != null) {
            require(tap.startSec.isFinite() && tap.endSec.isFinite() && tap.startSec >= 0f && tap.endSec > tap.startSec && tap.endSec <= durationSec) { "tap outside audio" }
            if (tap.endSec - tap.startSec < MIN_TAP_SEC) return PlanResult.Abstain(AbstainReason.REFERENCE_TOO_SHORT)
            val clusters = ca.windowClusters()
            val inside = ws.indices.filter { ws[it].startSec >= tap.startSec && ws[it].endSec <= tap.endSec }
            val valid = inside.filter { clusters[it] >= 0 }
            if (inside.size < MIN_TAP_WINDOWS || valid.size < MIN_TAP_WINDOWS) return PlanResult.Abstain(AbstainReason.TAP_NOT_IN_CLUSTER)
            val counts = valid.groupingBy { clusters[it] }.eachCount()
            val best = counts.maxByOrNull { it.value }!!
            if (best.value.toFloat() / inside.size < MIN_TAP_SHARE) return PlanResult.Abstain(AbstainReason.TAP_NOT_IN_CLUSTER)
            // Routed render needs other speakers to separate from; one cluster gives no such evidence.
            if (ca.clusterCount < 2) return PlanResult.Abstain(AbstainReason.SINGLE_CLUSTER)
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

    private fun finish(ca: ClusteredAnalysis, target: Int, src: TargetSource, ref: List<FootageAnalysis.Interval>): PlanResult {
        val ta = ca.forTarget(target)
        return PlanResult.Plan(target, ca.clusterCount, src, ref, ta.labels, ta.purity)
    }
}
