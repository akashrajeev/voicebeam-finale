package com.akashrajeev.voicebeam.core

import kotlin.math.max
import kotlin.math.min

/**
 * "Try this moment" hint shown when a tap is refused. It adds NO acceptance rule of its own: a candidate stretch is suggested only when the SAME planner
 * (tap path, with its tap checks and merge-stability check) returns a Plan for it AND the caller's reference check (the same guard the app applies to a tap)
 * passes. Passing says the stretch is one self-consistent voice that clusters stably. It does NOT say whose voice it is; the user must confirm that.
 */
object WindowSuggester {
    class Suggestion(val startSec: Float, val endSec: Float)

    val LENGTHS_SEC = floatArrayOf(8f, 5f)
    const val MAX_PLAN_CALLS = 80
    const val MAX_REFERENCE_CHECKS = 8
    const val MAX_SUGGESTIONS = 2

    fun suggest(
        ws: List<WindowEmbedding>, durationSec: Float, profile: OfflineFootagePlanner.MergeProfile,
        referencePasses: (Float, Float) -> Boolean, isCancelled: () -> Boolean = { false }
    ): List<Suggestion> {
        if (ws.isEmpty() || durationSec < 3f) return emptyList()
        val step = max(1f, durationSec / 40f)
        val planOk = ArrayList<Suggestion>()
        var calls = 0
        for (len in LENGTHS_SEC) {
            if (len > durationSec) continue
            var s = 0f
            while (s + len <= durationSec + 1e-3f && calls < MAX_PLAN_CALLS) {
                if (isCancelled()) return emptyList()
                calls++
                val r = OfflineFootagePlanner.plan(ws, durationSec, tap = FootageAnalysis.Interval(s, min(s + len, durationSec)), profile = profile)
                if (r is PlanResult.Plan && r.source == TargetSource.TAP) planOk.add(Suggestion(s, min(s + len, durationSec)))
                s += step
            }
        }
        val out = ArrayList<Suggestion>()
        var checks = 0
        for (c in planOk) {
            if (out.size >= MAX_SUGGESTIONS || checks >= MAX_REFERENCE_CHECKS || isCancelled()) break
            if (out.any { overlap(it, c) > 0.5f }) continue
            checks++
            if (referencePasses(c.startSec, c.endSec)) out.add(c)
        }
        return out
    }

    private fun overlap(a: Suggestion, b: Suggestion): Float {
        val o = min(a.endSec, b.endSec) - max(a.startSec, b.startSec)
        return if (o <= 0f) 0f else o / min(a.endSec - a.startSec, b.endSec - b.startSec)
    }

    fun note(found: List<Suggestion>): String =
        if (found.isEmpty()) "no 5-8 s stretch of this clip passes both checks"
        else "try " + found.joinToString(" or ") { "%.1f-%.1f s".format(it.startSec, it.endSec) } + " (one clean voice; confirm it is the person you want)"
}
