package com.akashrajeev.voicebeam.core

import org.junit.Assert.*
import org.junit.Test

class EnrolledPlannerTest {
    private fun v(vararg x: Float) = floatArrayOf(*x)
    // speaker A ~ (1,0.1,0), speaker B ~ (0,1,0.1); 60 s, 1 s windows hop 1 s, A speaks 0-30, B 30-60
    private fun ws(): List<WindowEmbedding> = (0 until 60).map { i ->
        val j = (i % 5) * 0.01f
        WindowEmbedding(i.toFloat(), i + 1f, if (i < 30) v(1f, 0.1f + j, 0f) else v(0f, 1f, 0.1f + j))
    }

    @Test fun picksTheEnrolledSpeaker() {
        val p = OfflineFootagePlanner.planEnrolled(ws(), 60f, v(1f, 0.1f, 0f))
        assertTrue(p is PlanResult.Plan); p as PlanResult.Plan
        assertEquals(TargetSource.ENROLLED, p.source)
        assertTrue(p.reference.all { it.endSec <= 30.01f })
        val q = OfflineFootagePlanner.planEnrolled(ws(), 60f, v(0f, 1f, 0.1f)) as PlanResult.Plan
        assertTrue(q.reference.all { it.startSec >= 29.99f })
    }

    @Test fun abstainsWhenTemplateMatchesNobody() {
        val p = OfflineFootagePlanner.planEnrolled(ws(), 60f, v(0f, 0f, 1f))
        assertTrue(p is PlanResult.Abstain)
    }

    @Test fun abstainsWhenTwoClustersMatchEquallyWell() {
        val p = OfflineFootagePlanner.planEnrolled(ws(), 60f, v(1f, 1f, 0f))
        assertTrue(p is PlanResult.Abstain)
    }

    @Test fun singleSpeakerClipAbstains() {
        val one = (0 until 30).map { WindowEmbedding(it.toFloat(), it + 1f, v(1f, 0.1f, 0f)) }
        assertTrue(OfflineFootagePlanner.planEnrolled(one, 30f, v(1f, 0.1f, 0f)) is PlanResult.Abstain)
    }
}
