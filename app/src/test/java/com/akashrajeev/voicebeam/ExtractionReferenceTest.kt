package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.engine.VoiceLearner
import org.junit.Assert.*
import org.junit.Test
class ExtractionReferenceTest {
    @Test fun onlySuccessfulDeliberateEnrollmentPersistsReference() {
        val l=VoiceLearner({floatArrayOf(1f,0f)},sampleRate=100,chunkSeconds=1f,needed=1)
        l.feed(FloatArray(100){.1f},1f);assertNull(l.extractionReference)
        l.beginEnrollment();l.feed(FloatArray(100){.1f},1f)
        assertTrue(l.learned);assertEquals(100,l.extractionReference!!.size)
        l.reset();assertNull(l.extractionReference)
    }
    @Test fun failedOrSilentEnrollmentNoReference() {
        val l=VoiceLearner({null},sampleRate=100,chunkSeconds=1f,needed=1)
        l.beginEnrollment();l.feed(FloatArray(100){.1f},1f);assertNull(l.extractionReference)
        l.feed(FloatArray(100),1f);assertNull(l.extractionReference)
    }
}
