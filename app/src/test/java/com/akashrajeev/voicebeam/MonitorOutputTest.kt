package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.MonitorOutput
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorOutputTest {
    private val speech = floatArrayOf(0.4f, -0.3f, 0.2f)
    private val silence = FloatArray(3)

    @Test fun unknownRouteGetsSilenceNotAnEmptyWrite() {
        val written = MonitorOutput.samples(true, false, speech, silence)
        assertSame(silence, written)
        assertTrue(written.isNotEmpty() && written.all { it == 0f })
    }

    @Test fun confirmedHeadphonesGetSpeech() {
        assertSame(speech, MonitorOutput.samples(true, true, speech, silence))
    }

    @Test fun mutedMonitorGetsSilenceEvenOnHeadphones() {
        assertSame(silence, MonitorOutput.samples(false, true, speech, silence))
    }

    @Test fun routeCanRecoverWithoutAllowingSpeakerFeedback() {
        for (headphones in listOf(false, false, true, true, false, true)) {
            assertSame(if (headphones) speech else silence,
                MonitorOutput.samples(true, headphones, speech, silence))
        }
    }
}
