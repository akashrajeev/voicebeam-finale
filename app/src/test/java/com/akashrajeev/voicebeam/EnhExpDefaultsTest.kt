package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.engine.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** enh-exp experimental defaults: stronger suppression out of the box, extraction off. */
class EnhExpDefaultsTest {
    @Test fun strongerSuppressionDefaults() {
        assertEquals(0.94f, Settings().quietOthers)
        assertEquals(0.85f, Settings().denoise)
    }

    @Test fun extractionStageOffByDefault() {
        assertFalse(Settings().tseExperiment)
    }
}
