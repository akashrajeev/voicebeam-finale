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
class OfflineVideoRoundTripTest {
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
    @Test fun importPreservesOriginalAndExportsSynchronizedVideo()=runBlocking {
        val a=fixture("1089-134686-0013.wav");val b=fixture("1221-135767-0005.wav")
        val n=minOf(a.size,b.size,16000*6);val frames=n/1600;val count=frames*1600
        val mix=FloatArray(count) { if(it<32000)a[it]*.5f else (a[it]+b[it])*.5f }
        val folder=File(context.cacheDir,"import-roundtrip").apply{mkdirs()}
        val silent=File(folder,"silent.mp4");val wav=File(folder,"mix.wav");val input=File(folder,"input.mp4")
        var session:com.akashrajeev.voicebeam.record.SessionMeta?=null
        try {
            blackVideo(silent,frames);WavWriter(wav,16000).use{it.write(mix)}
            MediaExporter.muxVideoWithWav(silent,wav,input)
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(input.readBytes())
            session=OfflineVideoImport.run(context,Uri.fromFile(input),0.0,2.0)
            assertArrayEquals(hash,java.security.MessageDigest.getInstance("SHA-256").digest(input.readBytes()))
            assertArrayEquals(hash,java.security.MessageDigest.getInstance("SHA-256").digest(File(session.dir,"original.mp4").readBytes()))
            assertTrue(session.video.length()>0);assertTrue(File(session.dir,"offline-reference.txt").exists())
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
