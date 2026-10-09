package com.akashrajeev.voicebeam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.core.*
import com.akashrajeev.voicebeam.engine.AudioPipeline
import com.akashrajeev.voicebeam.engine.Diagnostics
import com.akashrajeev.voicebeam.ml.AudioModels
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Test-only real AudioPipeline replay of raw.wav + supplied timestamped GateInputs.
 * No live camera/microphone, enrollment/query workers, UI, or voice inference.
 * Do not call this full audio-video engine replay. Video-to-input extraction is separate.
 */
@RunWith(AndroidJUnit4::class)
class SceneReplayTest {
    @Test fun replayRawWithFixedSignalTrace() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val args=InstrumentationRegistry.getArguments()
        val root=File(context.filesDir,"replay")
        val raw=File(root,"raw.wav");val signalFile=File(root,"signals.csv")
        require(raw.isFile&&signalFile.isFile){"Push raw.wav and signals.csv into files/replay first"}
        val (samples,sr)=WavWriter.read(raw);require(sr==16000&&samples.isNotEmpty())
        val lines=signalFile.readLines();val keys=lines.first().split(',')
        val signals=lines.drop(1).filter{it.isNotBlank()}.map{keys.zip(it.split(',')).toMap()}
        require(signals.isNotEmpty());require(signals.first().getValue("time_ms").toLong()==0L){"Trace must begin at audio time0; resolve video offset padding explicitly"};require(signals.zipWithNext().all{it.first.getValue("time_ms").toLong()<=it.second.getValue("time_ms").toLong()})
        val position=AtomicInteger(0);val completed=AtomicInteger(0);var traceIndex=0;var frameInputs:GateInputs?=null;val fault=AtomicReference<Throwable?>(null)
        val m=AudioModels.load(context.assets)
        val output=File(root,"frame_inputs.csv").bufferedWriter()
        output.write("time_ms,hasLock,lockedSpeaking,othersSpeaking,voiceMatch,voiceActive,lockedVisible,audioOnly,wearerMatch,wearerVetoEnabled,visionAgeMs,voiceLearned,actualGain,actualProbability\n")
        var p:AudioPipeline?=null
        try {
            Diagnostics.clear()
            val pipeline=AudioPipeline(m,context,signals={
                val ms=position.get()*1000L/16000
                while(traceIndex+1<signals.size&&signals[traceIndex+1].getValue("time_ms").toLong()<=ms)traceIndex++
                val r=signals[traceIndex]
                fun b(k:String)=r.getValue(k).toBooleanStrict()
                fun f(k:String)=r.getValue(k).toFloat()
                fun n(k:String)=r[k]?.takeIf{it.isNotEmpty()}?.toFloat()
                GateInputs(b("hasLock"),f("lockedSpeaking"),f("othersSpeaking"),n("voiceMatch"),false,
                    lockedVisible=b("lockedVisible") && r.getValue("visionAgeMs").toLong()+ms-r.getValue("time_ms").toLong()<400,audioOnly=b("audioOnly"),wearerMatch=n("wearerMatch"),
                    wearerVetoEnabled=b("wearerVetoEnabled"),visionAgeMs=r.getValue("visionAgeMs").toLong().let{if(it<0) -1 else it+ms-r.getValue("time_ms").toLong()},voiceLearned=b("voiceLearned")).also{frameInputs=it}
            },onError={fault.set(it)}) { frame ->
                val i=frameInputs ?: error("no gate inputs")
                output.write(listOf(position.get()*1000L/16000,i.hasLock,i.lockedSpeaking,i.othersSpeaking,i.voiceMatch ?: "",
                    frame.voiceActive,i.lockedVisible,i.audioOnly,i.wearerMatch ?: "",i.wearerVetoEnabled,i.visionAgeMs,i.voiceLearned,frame.gain,frame.probability).joinToString(",")+"\n")
                completed.set(position.get())
            }
            p=pipeline
            pipeline.quietOthers=args.getString("quietOthers")?.toFloat() ?: .86f
            pipeline.denoiseMix=args.getString("denoiseMix")?.toFloat() ?: .7f
            pipeline.boostDb=args.getString("boostDb")?.toFloat() ?: 6f
            pipeline.debugFeed={count -> FloatArray(count).also{out ->
                val pos=position.get();val n=minOf(count,(samples.size-pos).coerceAtLeast(0));if(n>0)samples.copyInto(out,0,pos,pos+n);position.addAndGet(count)
            }}
            pipeline.enrollmentActive={false};pipeline.enrollmentStatus={"replay_fixed_inputs_no_workers"}
            pipeline.start(false)
            val timeout=android.os.SystemClock.elapsedRealtime()+samples.size*1000L/16000+30000
            while(completed.get()<samples.size&&fault.get()==null&&android.os.SystemClock.elapsedRealtime()<timeout)Thread.sleep(50)
            require(completed.get()>=samples.size){"replay incomplete"};fault.get()?.let{throw it}
        } finally {
            p?.stop();output.close();File(root,"diagnostics.txt").writeText(Diagnostics.snapshot());m.release()
        }
    }
}
