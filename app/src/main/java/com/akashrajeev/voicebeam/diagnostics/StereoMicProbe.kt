package com.akashrajeev.voicebeam.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.SystemClock
import com.akashrajeev.voicebeam.core.StereoProbeMath
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

/** Opt-in diagnostic only. Samples stay in memory; only measurements are written. */
object StereoMicProbe {
    suspend fun run(context: Context, onProgress: (String)->Unit): File = withContext(Dispatchers.IO) {
        require(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) { "Microphone permission required" }
        val manager=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val report=StringBuilder("VoiceBeam stereo microphone probe\n${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT}\nNo audio saved. A distinct-channel verdict is NOT proof of beamforming or speaker isolation. Speak from the left for the first half and right for the second half of each capture.\n")
        fun mic(m:MicrophoneInfo):String = "description=${m.description} type=${m.type} address=${m.address} location=${m.location} position=${m.position.x},${m.position.y},${m.position.z} orientation=${m.orientation.x},${m.orientation.y},${m.orientation.z} mapping=${m.channelMapping.joinToString { "${it.first}:${it.second}" }}"
        if(Build.VERSION.SDK_INT>=28) try { report.appendLine("Available microphones:");manager.microphones.forEach { report.appendLine(mic(it)) } } catch(e:Exception){report.appendLine("Mic inventory unavailable: ${e.javaClass.simpleName}")}
        val sources=listOf("CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,"MIC" to MediaRecorder.AudioSource.MIC,"UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,"VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION)
        fun summary(l:FloatArray,r:FloatArray):String {
            val a=StereoProbeMath.analyze(l,r)
            return String.format(Locale.US,"left=%.2f dBFS right=%.2f dBFS ILD=%.2f dB lag=%d samples corr=%.5f verdict=%s",a.rmsLeftDb,a.rmsRightDb,a.ildDb,a.bestLagSamples,a.peakCorrelation,a.verdict)
        }
        for((name,source) in sources) for(rate in listOf(48000,16000)) {
            ensureActive();var recorder:AudioRecord?=null
            report.appendLine("\n$name requestedRate=$rate requestedChannels=2")
            try {
                val min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT)
                require(min>0){"Stereo format unsupported: $min"}
                val rec=AudioRecord.Builder().setAudioSource(source).setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(min*2,rate/5*4)).build();recorder=rec
                require(rec.state==AudioRecord.STATE_INITIALIZED){"AudioRecord initialization failed"}
                rec.startRecording();require(rec.recordingState==AudioRecord.RECORDSTATE_RECORDING){"Capture did not start"}
                report.appendLine("actualRate=${rec.sampleRate} channelCount=${rec.channelCount} session=${rec.audioSessionId} routedDevice=${rec.routedDevice?.type}")
                if(Build.VERSION.SDK_INT>=28) try { rec.activeMicrophones.forEach { report.appendLine("Active mic: "+mic(it)) } } catch(e:Exception){report.appendLine("Active mapping unavailable: ${e.javaClass.simpleName}")}
                try { manager.activeRecordingConfigurations.filter { it.clientAudioSessionId==rec.audioSessionId }.forEach { c -> report.appendLine("RecordingConfig source=${c.clientAudioSource} clientRate=${c.clientFormat.sampleRate} clientChannels=${c.clientFormat.channelCount} deviceRate=${c.format.sampleRate} deviceChannels=${c.format.channelCount}"+if(Build.VERSION.SDK_INT>=29) " silenced=${c.isClientSilenced}" else "") } } catch(e:Exception){report.appendLine("Recording config unavailable: ${e.javaClass.simpleName}")}
                require(rec.channelCount==2){"Device returned non-stereo channels"}
                val data=ShortArray(rate*5*2);var filled=0;val deadline=SystemClock.elapsedRealtime()+8000;var lastHalf=-1
                while(filled<data.size && SystemClock.elapsedRealtime()<deadline) {
                    ensureActive();val half=if(filled<data.size/2)0 else 1
                    if(half!=lastHalf){onProgress("$name $rate Hz: speak from ${if(half==0) "LEFT" else "RIGHT"} side");lastHalf=half}
                    val n=rec.read(data,filled,minOf(4096,data.size-filled),AudioRecord.READ_NON_BLOCKING)
                    require(n>=0){"Audio read failed: $n"};if(n==0)delay(10) else filled+=n
                }
                rec.stop();ensureActive();val frames=filled/2
                require(frames>=rate*4){"Too little captured audio: $frames frames"}
                val left=FloatArray(frames){data[it*2]/32768f};val right=FloatArray(frames){data[it*2+1]/32768f}
                report.appendLine("frames=$frames durationSec=${frames.toDouble()/rec.sampleRate} "+summary(left,right))
                val mid=frames/2;val a=StereoProbeMath.analyze(left.copyOfRange(0,mid),right.copyOfRange(0,mid));val b=StereoProbeMath.analyze(left.copyOfRange(mid,frames),right.copyOfRange(mid,frames))
                report.appendLine("First half: "+summary(left.copyOfRange(0,mid),right.copyOfRange(0,mid)))
                report.appendLine("Second half: "+summary(left.copyOfRange(mid,frames),right.copyOfRange(mid,frames)))
                report.appendLine("Direction differs between halves: ${StereoProbeMath.speakersDirectionallySeparable(a,b)} (only meaningful if speakers followed the prompts; uncalibrated thresholds)")
            } catch(e:CancellationException){throw e} catch(e:Exception){report.appendLine("Unavailable/failed: ${e.javaClass.simpleName}: ${e.message}")}
            finally { try { recorder?.stop() } catch(_:Exception){};recorder?.release() }
        }
        ensureActive();val dir=File(context.cacheDir,"exports").also{it.mkdirs()};File(dir,"voicebeam-stereo-probe.txt").also{it.writeText(report.toString())}
    }
}
