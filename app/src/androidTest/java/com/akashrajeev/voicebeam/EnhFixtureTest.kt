package com.akashrajeev.voicebeam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.core.*
import com.akashrajeev.voicebeam.engine.VoiceLearner
import com.akashrajeev.voicebeam.ml.Denoiser
import com.akashrajeev.voicebeam.ml.VoicePrint
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import kotlin.math.*
import kotlin.random.Random

/** Public real speech, synthetic mixtures/noise and SCRIPTED lip states. Not real-room proof. */
@RunWith(AndroidJUnit4::class)
class EnhFixtureTest {
    @Test fun matchedEnhancementArms() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        fun load(id: String): FloatArray {
            val f = File(ctx.cacheDir, "$id.wav")
            ctx.assets.open("enhfixtures/$id.wav").use { i -> f.outputStream().use { i.copyTo(it) } }
            val (v, sr) = WavWriter.read(f); assertEquals(16000, sr)
            val scale = .06f / rms(v).coerceAtLeast(.00001f)
            return FloatArray(64000) { v[it % v.size] * scale }
        }
        val target = load("1089-134686-0002")
        val other = load("1221-135767-0005")
        val enrollment = load("1089-134686-0013")
        val vp = VoicePrint(ctx.assets); val d = Denoiser(ctx.assets)
        val report = StringBuilder("ENH-7 PUBLIC FIXTURE TEST; 4s, RMS=.06; synthetic equal-RMS overlap/noise; scripted lips. Not Indian voices, not Bluetooth or end-to-end latency.\n")
        try {
            val learner = VoiceLearner({ vp.embed(it) }, 16000)
            learner.beginEnrollment()
            repeat(9) { sec -> learner.feed(FloatArray(16000) { enrollment[(sec * 16000 + it) % enrollment.size] }, 0f) }
            assertTrue("raw enrollment should complete despite lip0", learner.learned)
            report.append("enrollment=LEARNED phases=${learner.completedPhrases}\n")
            val centroid = learner.centroid!!
            val rnd = Random(7)
            val noise = FloatArray(64000) { (rnd.nextFloat() - .5f) * .12f }
            val fixtures = listOf("target" to target, "other" to other,
                "overlap" to FloatArray(64000) { target[it]+other[it] },
                "target_noise" to FloatArray(64000) { target[it]+noise[it] },
                "noise" to noise, "silence" to FloatArray(64000),
                "target_enters" to FloatArray(64000) { if(it<32000) other[it] else target[it] })
            for ((name, raw) in fixtures) {
                val start=System.nanoTime(); val e=vp.embed(raw.copyOfRange(0,48000)); val embedMs=(System.nanoTime()-start)/1e6
                val score=e?.let { VoiceMatch.score(VoiceMatch.cosine(it,centroid)) }
                report.append("fixture=$name score=$score embedMs=$embedMs\n")
                for (mix in listOf(0f,.7f,.8f,.9f,1f)) for (db in listOf(0f,6f,12f)) {
                    d.reset(); val alignment=DenoiseAlignment(); val gate=TargetGate(frameMs=d.frameShift*1000f/16000)
                    gate.quietOthers=.86f
                    val envelope=ListenEnvelope(); val output=ArrayList<Float>(); val times=ArrayList<Double>(); var off=0;var muted=0;var frames=0;var clips=0
                    while(off+d.frameShift<=raw.size) {
                        val input=raw.copyOfRange(off,off+d.frameShift)
                        alignment.push(input)
                        val t=System.nanoTime();val den=d.process(input);times.add((System.nanoTime()-t)/1e6)
                        val n=minOf(input.size,den.size)
                        if(n>0) {
                            val clean=FloatArray(n);alignment.mix(den,mix,clean,n)
                            val targetPresent=name=="target"||name=="target_noise"||name=="overlap"||(name=="target_enters"&&off>=32000)
                            val otherPresent=name=="other"||name=="overlap"||(name=="target_enters"&&off<32000)
                            val g=gate.process(GateInputs(true,if(targetPresent).9f else 0f,if(otherPresent).9f else 0f,if (name=="target_enters"&&off>=32000) null else score,rms(input)>.005f,voiceLearned=true))
                            if(targetPresent&&g<.1f)muted++
                            val boost=FrameDsp.safeBoost(clean,n,g,if(gate.boostAllowed)TargetGate.dbToLinear(db) else 1f)
                            val gated=FloatArray(n);val out=FloatArray(n);envelope.process(clean,n,g,if(gate.boostAllowed)TargetGate.dbToLinear(db) else 1f,gated,out)
                            for(v in out){if(abs(v)>=.999f)clips++;output.add(v)}
                            frames++
                        };off+=d.frameShift
                    }
                    val out=output.toFloatArray();times.sort()
                    val rmsDb=20*log10((rms(out)/rms(raw).coerceAtLeast(1e-8f)).coerceAtLeast(1e-8f))
                    val preservation=if(name=="target"||name=="target_noise") bestCorrelation(target,out) else Double.NaN
                    report.append("arm $name mix=$mix boostDb=$db outputGainDb=$rmsDb scriptedFalseMute=$muted/$frames targetCorrelation=$preservation clipped=$clips sepDenoiseP95Ms=${times[(times.size*.95).toInt().coerceAtMost(times.lastIndex)]}\n")
                    assertEquals("no hard clips $name",0,clips)
                    assertTrue("finite output",out.all { it.isFinite() })
                }
            }
            File(ctx.getExternalFilesDir(null),"ENH-7-fixtures.txt").writeText(report.toString())
            for(line in report.lines()) android.util.Log.i("ENH_FIXTURE",line)
        } finally { vp.release();d.release() }
    }
    private fun rms(v: FloatArray): Float = sqrt(v.sumOf { it.toDouble()*it }/v.size.coerceAtLeast(1)).toFloat()
    private fun bestCorrelation(ref: FloatArray,out: FloatArray): Double {
        var best=0.0
        for(lag in -1024..1024 step 16) {
            val refOff=maxOf(0,-lag);val outOff=maxOf(0,lag)
            val n=minOf(ref.size-refOff,out.size-outOff);if(n<16000)continue
            var dot=0.0;var a=0.0;var b=0.0
            for(i in 0 until n){val x=ref[i+refOff];val y=out[i+outOff];dot+=x*y;a+=x*x;b+=y*y}
            best=maxOf(best,abs(dot)/sqrt((a*b).coerceAtLeast(1e-20)))
        };return best
    }
}
