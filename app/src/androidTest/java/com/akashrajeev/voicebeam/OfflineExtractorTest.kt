package com.akashrajeev.voicebeam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.core.WavWriter
import com.akashrajeev.voicebeam.separation.OfflineSpeakerBeam
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class OfflineExtractorTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(name:String):FloatArray {
        val f=File(context.cacheDir,name)
        context.assets.open("enhfixtures/"+name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return WavWriter.read(f).first
    }
    private fun sisdr(y:FloatArray,t:FloatArray):Double {
        val ym=y.average();val tm=t.average();var dot=0.0;var tt=0.0
        for(i in y.indices) {dot+=(y[i]-ym)*(t[i]-tm);tt+=(t[i]-tm).pow(2)}
        val scale=dot/(tt+1e-9);var sig=0.0;var err=0.0
        for(i in y.indices){val p=scale*(t[i]-tm);sig+=p*p;err+=(y[i]-ym-p).pow(2)}
        return 10*log10((sig+1e-9)/(err+1e-9))
    }
    @Test fun realOnnxTwoSpeakerRecordingExtractsAndPreservesLength() {
        val a=fixture("1089-134686-0002.wav");val b=fixture("1221-135767-0005.wav")
        val ref=fixture("1089-134686-0013.wav");val n=minOf(a.size,b.size)
        val mix=FloatArray(n){(a[it]+b[it])*.5f};val raw=File(context.cacheDir,"tse-mix.wav")
        WavWriter(raw,16000).use {it.write(mix)}
        val out=File(context.cacheDir,"tse-result.wav");out.delete()
        val ms=OfflineSpeakerBeam.extract(context,raw,ref,out)
        val (result,sr)=WavWriter.read(out)
        assertEquals(16000,sr);assertEquals(n,result.size);assertTrue(result.all{it.isFinite()})
        val before=sisdr(mix,a.copyOf(n));val after=sisdr(result,a.copyOf(n))
        android.util.Log.i("TseTest","offline fixture before="+before+" after="+after+" ms="+ms)
        assertTrue("Actual target extraction must improve fixture by >3dB",after>before+3)
        assertTrue(raw.exists());out.delete();raw.delete()
    }
    @Test fun badReferenceLeavesOriginalUntouched() {
        val raw=File(context.cacheDir,"tse-guard.wav");WavWriter(raw,16000).use {it.write(FloatArray(16000){.1f})}
        val out=File(context.cacheDir,"tse-invalid.wav");out.delete()
        try { OfflineSpeakerBeam.extract(context,raw,FloatArray(48000),out);fail("silent enrollment accepted") }
        catch(_:IllegalArgumentException){}
        assertFalse(out.exists());assertTrue(raw.exists());raw.delete()
    }
}
