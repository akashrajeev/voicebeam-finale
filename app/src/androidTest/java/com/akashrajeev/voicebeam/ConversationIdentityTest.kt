package com.akashrajeev.voicebeam
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.core.*
import com.akashrajeev.voicebeam.ml.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class ConversationIdentityTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(name:String):FloatArray {
        val f=File(context.cacheDir,name);context.assets.open("enhfixtures/"+name).use{input->f.outputStream().use{input.copyTo(it)}}
        return WavWriter.read(f).first
    }
    @Test fun freshTwoSecondEmbeddingsSeparateKnownSpeakers() {
        val print=VoicePrint(context.assets);val clean=Denoiser(context.assets)
        fun denoise(x:FloatArray):FloatArray {
            clean.reset();val y=FloatArray(x.size);var a=0
            while(a<x.size){val n=minOf(clean.frameShift,x.size-a);val block=FloatArray(clean.frameShift);x.copyInto(block,0,a,a+n);val out=clean.process(block);out.copyInto(y,a,0,minOf(n,out.size));a+=n};return y
        }
        try {
            val ref=denoise(fixture("1089-134686-0013.wav"));val a=denoise(fixture("1089-134686-0002.wav"));val b=denoise(fixture("1221-135767-0005.wav"))
            val enrolled=print.embed(ref.copyOfRange(0,48000))!!
            val start=android.os.SystemClock.elapsedRealtime()
            val ascore=SpeakerProfile.DEFAULT.score(VoiceMatch.cosine(enrolled,print.embed(a.copyOfRange(16000,48000))!!))
            val bscore=SpeakerProfile.DEFAULT.score(VoiceMatch.cosine(enrolled,print.embed(b.copyOfRange(16000,48000))!!))
            assertTrue(ascore>=.8f);assertTrue(bscore<=.35f)
            val gate=TargetGate().apply{tuning=GateTuning(conversationCandidate=true)}
            gate.process(GateInputs(true,0f,0f,ascore,true,voiceScoreSequence=1,voiceScoreAgeMs=0,voiceQuerySamples=32000));assertEquals(TargetState.TARGET,gate.state)
            gate.process(GateInputs(true,0f,0f,bscore,true,voiceScoreSequence=2,voiceScoreAgeMs=0,voiceQuerySamples=40000));assertEquals(TargetState.OTHER,gate.state)
            android.util.Log.i("ConversationTest","targetScore=$ascore otherScore=$bscore twoQueryMs="+(android.os.SystemClock.elapsedRealtime()-start))
        } finally{print.release();clean.release()}
    }
}
