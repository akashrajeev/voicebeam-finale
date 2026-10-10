package com.akashrajeev.voicebeam

import android.media.*
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.core.WavWriter
import com.akashrajeev.voicebeam.record.MediaExporter
import com.akashrajeev.voicebeam.separation.OfflineVideoImport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Generates a real MP4, then exercises decode -> target reference -> extraction -> export. */
class FootageImportRoundTripTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(name:String):FloatArray {
        val f=File(context.cacheDir,name)
        context.assets.open("enhfixtures/"+name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return WavWriter.read(f).first
    }
    private fun blackVideo(out: File, frames: Int) {
        val w=160; val h=120
        val format=MediaFormat.createVideoFormat("video/avc",w,h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE,100000);setInteger(MediaFormat.KEY_FRAME_RATE,10);setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
        }
        val codec=MediaCodec.createEncoderByType("video/avc")
        val muxer=MediaMuxer(out.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track=-1;var frame=0;var done=false;var started=false;var eos=false
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
            val info=MediaCodec.BufferInfo();val clock=android.os.SystemClock.elapsedRealtime()
            while(!done) {
                require(android.os.SystemClock.elapsedRealtime()-clock<30000)
                val index=if(eos)-1 else codec.dequeueInputBuffer(10000)
                if(index>=0) {
                    val b=codec.getInputBuffer(index)!!;b.clear()
                    if(frame<frames) {
                        b.put(ByteArray(w*h){16});b.put(ByteArray(w*h/2){128.toByte()})
                        codec.queueInputBuffer(index,0,w*h*3/2,frame*100000L,0);frame++
                    } else { codec.queueInputBuffer(index,0,0,frame*100000L,MediaCodec.BUFFER_FLAG_END_OF_STREAM);eos=true }
                }
                val output=codec.dequeueOutputBuffer(info,10000)
                if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track=muxer.addTrack(codec.outputFormat);muxer.start();started=true }
                else if(output>=0) {
                    try {
                        if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                            val b=codec.getOutputBuffer(output)!!;b.position(info.offset);b.limit(info.offset+info.size);muxer.writeSampleData(track,b,info)
                        }
                        done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    } finally {codec.releaseOutputBuffer(output,false)}
                }
            }
        } finally { if(started)muxer.stop();muxer.release();codec.stop();codec.release() }
    }
    /** A real AAC MP4 with its audio track 300ms late, previously rejected at 50ms. */
    @Test fun lateAudioAlignsAndExportsOnVideoTimeline(): Unit=runBlocking {
        val audio=fixture("1089-134686-0013.wav").copyOf(16000*6)
        val folder=File(context.cacheDir,"offset-roundtrip").apply{mkdirs()}
        var session:com.akashrajeev.voicebeam.record.SessionMeta?=null
        try {
            val silent=File(folder,"silent.mp4");val wav=File(folder,"audio.wav")
            val base=File(folder,"base.mp4");val shifted=File(folder,"shifted.mp4")
            blackVideo(silent,60);WavWriter(wav,16000).use{it.write(audio)}
            MediaExporter.muxVideoWithWav(silent,wav,base)
            val ex=MediaExtractor();val mux=MediaMuxer(shifted.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                ex.setDataSource(base.absolutePath)
                val tracks=(0 until ex.trackCount).map{mux.addTrack(ex.getTrackFormat(it))};mux.start()
                val buffer=java.nio.ByteBuffer.allocate(1024*1024);val info=MediaCodec.BufferInfo()
                for (i in tracks.indices) {
                    ex.selectTrack(i)
                    val late=ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
                    while(true) {
                        buffer.clear();val n=ex.readSampleData(buffer,0);if(n<0)break
                        info.set(0,n,ex.sampleTime+(if(late)300000L else 0L),ex.sampleFlags)
                        mux.writeSampleData(tracks[i],buffer,info);ex.advance()
                    }
                    ex.unselectTrack(i)
                }
                mux.stop()
            } finally {mux.release();ex.release()}
            val evidence=File(context.getExternalFilesDir(null),"offset-evidence").apply{mkdirs()}
            fun pts(file:File):String {
                val e=MediaExtractor();try {
                    e.setDataSource(file.absolutePath)
                    return (0 until e.trackCount).joinToString { i ->
                        e.selectTrack(i);val first=e.sampleTime;e.unselectTrack(i)
                        e.getTrackFormat(i).getString(MediaFormat.KEY_MIME)+"="+first
                    }
                } finally {e.release()}
            }
            val ptsLog="base:"+pts(base)+" shifted:"+pts(shifted)
            android.util.Log.i("OffsetEvidence",ptsLog)
            File(evidence,"pts.txt").writeText(ptsLog)
            val decoded=com.akashrajeev.voicebeam.separation.VideoAudioDecoder.decode(shifted)
            assertEquals(96000,decoded.size)
            assertTrue("Late audio must begin with silence",decoded.take(1600).all{kotlin.math.abs(it)<.001f})
            // Same codec payload shifted in presentation time: decoded speech must shift by exactly .3 seconds.
            val baseline=com.akashrajeev.voicebeam.separation.VideoAudioDecoder.decode(base)
            var err=0.0;var energy=0.0
            for(i in 1600 until 64000) {val d=decoded[i+4800]-baseline[i];err+=d*d;energy+=baseline[i]*baseline[i]}
            WavWriter(File(evidence,"baseline.wav"),16000).use{it.write(baseline)}
            WavWriter(File(evidence,"shifted.wav"),16000).use{it.write(decoded)}
            var bestLag=0;var bestError=Double.POSITIVE_INFINITY
            for(lag in -1024..1024) {
                var le=0.0;var en=0.0
                for(i in 1600 until 64000) {
                    val j=i+4800+lag;if(j !in decoded.indices)continue
                    val d=decoded[j]-baseline[i];le+=d*d;en+=baseline[i]*baseline[i]
                }
                val score=le/(en+1e-9);if(score<bestError){bestError=score;bestLag=lag}
            }
            val note="nominalError="+(err/(energy+1e-9))+" bestLagSamples="+bestLag+" bestError="+bestError
            android.util.Log.i("OffsetEvidence",note);File(evidence,"error.txt").writeText(note)
            assertTrue("Video timeline speech mismatch",err/(energy+1e-9)<.0001)
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(shifted.readBytes())
            val result=com.akashrajeev.voicebeam.separation.OfflineFootageImport.run(context,Uri.fromFile(shifted),.3,3.3)
            assertTrue(result is com.akashrajeev.voicebeam.separation.FootageResult.Done)
            result as com.akashrajeev.voicebeam.separation.FootageResult.Done;session=result.meta
            assertArrayEquals(hash,java.security.MessageDigest.getInstance("SHA-256").digest(File(session.dir,"original.mp4").readBytes()))
            assertTrue(session.video.length()>0)
            val output=MediaExtractor()
            try {
                output.setDataSource(session.video.absolutePath)
                val ts=(0 until output.trackCount).map{output.getTrackFormat(it).getLong(MediaFormat.KEY_DURATION)}
                assertEquals(2,ts.size);assertTrue(kotlin.math.abs(ts[0]-ts[1])<=100000)
            } finally {output.release()}
            android.util.Log.i("FootageNative","offset300ms aligned; exported; routed="+result.routed+" note="+result.note)
        } finally {session?.dir?.deleteRecursively();folder.deleteRecursively()}
    }
    @Test fun cancelBeforeExtractionDoesNotWriteOutput() {
        val out=File(context.cacheDir,"cancel-no-output.wav");out.delete()
        try {
            com.akashrajeev.voicebeam.separation.OfflineSpeakerBeam.extract(context,File(context.cacheDir,"unused.wav"),FloatArray(16000),out,isCancelled={true})
            fail("Expected cancellation")
        } catch(e:kotlinx.coroutines.CancellationException) { assertFalse(out.exists()) }
    }
    @Test fun importPreservesOriginalAndExportsSynchronizedVideo()=runBlocking {
        val a=fixture("1089-134686-0013.wav");val b=fixture("1221-135767-0005.wav")
        val n=minOf(a.size,b.size,16000*6);val frames=n/1600;val count=frames*1600
        val mix=FloatArray(count) { if(it<48000)a[it]*.5f else (a[it]+b[it])*.5f }
        val folder=File(context.cacheDir,"import-roundtrip").apply{mkdirs()}
        val silent=File(folder,"silent.mp4");val wav=File(folder,"mix.wav");val input=File(folder,"input.mp4")
        var session:com.akashrajeev.voicebeam.record.SessionMeta?=null
        try {
            blackVideo(silent,frames);WavWriter(wav,16000).use{it.write(mix)}
            MediaExporter.muxVideoWithWav(silent,wav,input)
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(input.readBytes())
            val result=com.akashrajeev.voicebeam.separation.OfflineFootageImport.run(context,Uri.fromFile(input),0.0,3.0)
            assertTrue(result is com.akashrajeev.voicebeam.separation.FootageResult.Done)
            result as com.akashrajeev.voicebeam.separation.FootageResult.Done
            session=result.meta
            android.util.Log.i("FootageNative", "routed="+result.routed+" note="+result.note)
            assertArrayEquals(hash,java.security.MessageDigest.getInstance("SHA-256").digest(input.readBytes()))
            assertArrayEquals(hash,java.security.MessageDigest.getInstance("SHA-256").digest(File(session.dir,"original.mp4").readBytes()))
            val fallback=File(session.dir,"offline-fallback.txt")
            assertFalse("Known clean reference must not silently fallback: "+(if(fallback.exists())fallback.readText() else "none"),fallback.exists())
            assertTrue(session.video.length()>0);assertTrue(File(session.dir,"offline-reference.txt").exists() || File(session.dir,"footage-report.txt").exists())
            val ex=MediaExtractor()
            try {
                ex.setDataSource(session.video.absolutePath)
                val times=(0 until ex.trackCount).associate { i -> ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)!! to ex.getTrackFormat(i).getLong(MediaFormat.KEY_DURATION) }
                assertEquals(2,times.size)
                val video=times.entries.first{it.key.startsWith("video/")}.value
                val audio=times.entries.first{it.key.startsWith("audio/")}.value
                assertTrue("Export audio/video drift",kotlin.math.abs(video-audio)<=100000)
            } finally {ex.release()}
        } finally { session?.dir?.deleteRecursively();folder.deleteRecursively() }
    }
}
