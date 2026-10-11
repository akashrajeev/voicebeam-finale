package com.akashrajeev.voicebeam.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class WindowSuggesterTest {
    private fun vec(angle: Double, jitter: Double) = floatArrayOf(cos(angle + jitter).toFloat(), sin(angle + jitter).toFloat(), 0.05f)
    /** 0.5 s hop, 1.5 s windows over 30 s: voice A (angle 0) 0-12 s and 20-30 s, voice B (angle 2) 12-20 s. */
    private fun windows(): List<WindowEmbedding> {
        val out = ArrayList<WindowEmbedding>(); var t = 0f; var n = 0
        while (t + 1.5f <= 30f) {
            val mid = t + 0.75f
            out.add(WindowEmbedding(t, t + 1.5f, if (mid in 12f..20f) vec(2.0, (n % 3) * 0.03) else vec(0.0, (n % 3) * 0.03))); t += 0.5f; n++
        }
        return out
    }
    private val profile = OfflineFootagePlanner.MergeProfile.RAW

    @Test fun suggestionsPassThePlannerAndTheReferenceCheck() {
        val ws = windows()
        var checks = 0
        val s = WindowSuggester.suggest(ws, 30f, profile, { _, _ -> checks++; true })
        assertTrue(s.isNotEmpty())
        assertTrue(s.size <= WindowSuggester.MAX_SUGGESTIONS)
        for (x in s) assertTrue(OfflineFootagePlanner.plan(ws, 30f, tap = FootageAnalysis.Interval(x.startSec, x.endSec), profile = profile) is PlanResult.Plan)
        assertTrue(checks <= WindowSuggester.MAX_REFERENCE_CHECKS)
    }
    @Test fun aFailingReferenceCheckSuppressesEverySuggestionAndIsBounded() {
        var checks = 0
        val s = WindowSuggester.suggest(windows(), 30f, profile, { _, _ -> checks++; false })
        assertTrue(s.isEmpty())
        assertTrue(checks <= WindowSuggester.MAX_REFERENCE_CHECKS)
        assertTrue(WindowSuggester.note(s).contains("no 5-8 s stretch"))
    }
    @Test fun noteNamesTheWindows() {
        val n = WindowSuggester.note(listOf(WindowSuggester.Suggestion(14f, 20f)))
        assertTrue(n, n.startsWith("try 14.0-20.0 s"))
    }
    @Test fun shortOrEmptyInputGivesNothing() {
        assertTrue(WindowSuggester.suggest(emptyList(), 30f, profile, { _, _ -> true }).isEmpty())
        assertTrue(WindowSuggester.suggest(windows(), 2f, profile, { _, _ -> true }).isEmpty())
    }
}
