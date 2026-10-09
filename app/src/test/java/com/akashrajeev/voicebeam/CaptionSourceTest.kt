package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.CaptionSource
import org.junit.Assert.*
import org.junit.Test
class CaptionSourceTest {
    @Test fun rawRemainsUnchangedAndCopied() {
        val raw=floatArrayOf(.3f,.4f);val out=CaptionSource.samples(raw,floatArrayOf(.1f),false)!!
        assertArrayEquals(raw,out,0f);out[0]=9f;assertEquals(.3f,raw[0],0f)
    }
    @Test fun denoisedUsesPurePregateAudioAndCopies() {
        val clean=floatArrayOf(.01f,.02f);val out=CaptionSource.samples(floatArrayOf(.9f),clean,true)!!
        assertArrayEquals(clean,out,0f);out[0]=9f;assertEquals(.01f,clean[0],0f)
    }
    @Test fun warmupNeverSilentlySubstitutesRawInDenoisedLane() {
        assertNull(CaptionSource.samples(floatArrayOf(.5f),floatArrayOf(),true))
        assertNotNull(CaptionSource.samples(floatArrayOf(.5f),floatArrayOf(),false))
    }
}
