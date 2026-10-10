package com.akashrajeev.voicebeam.footage

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioFormat
import com.akashrajeev.voicebeam.core.WavWriter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/** Platform media decoder. Imported video unchanged; analysis audio is a local 16 kHz mono copy. */
object FootageAudio {
    fun decode(video: File,wav: File,cancelled: ()->Boolean,progress: (Long)->Unit): Long {
        val extractor=MediaExtractor();var codec: MediaCodec?=null
        try {
            extractor.setDataSource(video.path)
            val track=(0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true }
                ?:error("This video has no supported audio track. Choose a video with sound.")
            extractor.selectTrack(track);val format=extractor.getTrackFormat(track)
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding=AudioFormat.ENCODING_PCM_16BIT;var resampler=FootageResampler(rate)
            val decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!);codec=decoder
            decoder.configure(format,null,null,0);decoder.start()
            var inputDone=false;var outputDone=false;val info=MediaCodec.BufferInfo()
            val started=android.os.SystemClock.elapsedRealtime();var lastProgress=0L;var duration=0L
            WavWriter(wav,16000).use { writer ->
                while(!outputDone) {
                    check(!cancelled()) { "Import cancelled. Original file on your phone is unchanged." }
                    check(android.os.SystemClock.elapsedRealtime()-started<600000) { "Audio decode timed out. Try a shorter clip." }
                    check(wav.parentFile!!.usableSpace>30_000_000) { "Free storage and retry this import." }
                    if(!inputDone) {
                        val index=decoder.dequeueInputBuffer(10000)
                        if(index>=0) {
                            val buffer=decoder.getInputBuffer(index)!!;val n=extractor.readSampleData(buffer,0)
                            if(n<0) { decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true }
                            else { decoder.queueInputBuffer(index,0,n,extractor.sampleTime,0);extractor.advance() }
                        }
                    }
                    when(val index=decoder.dequeueOutputBuffer(info,10000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val output=decoder.outputFormat;val newRate=output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels=output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding=if(output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            require(encoding==AudioFormat.ENCODING_PCM_16BIT || encoding==AudioFormat.ENCODING_PCM_FLOAT) { "Unsupported decoded audio format. Try MP4/AAC." }
                            if(newRate!=rate) { rate=newRate;resampler=FootageResampler(rate) }
                        }
                        else -> if(index>=0) {
                            val buffer=decoder.getOutputBuffer(index)!!;buffer.order(ByteOrder.nativeOrder());buffer.position(info.offset);buffer.limit(info.offset+info.size)
                            val unit=if(encoding==AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val mono=FloatArray(info.size/(unit*channels))
                            for(i in mono.indices) { var sum=0f;repeat(channels) { sum+=if(unit==4) buffer.float else buffer.short/32768f };mono[i]=(sum/channels).coerceIn(-1f,1f) }
                            writer.write(resampler.add(mono));duration=writer.durationMs
                            if(duration-lastProgress>=1000) { progress(duration);lastProgress=duration }
                            // First lab bounds import processing, not live Recall recording.
                            check(duration<=15*60*1000) { "This lab supports up to 15-minute imports. Choose a shorter clip." }
                            outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                            decoder.releaseOutputBuffer(index,false)
                        }
                    }
                }
            }
            require(duration>0) { "The video audio track is empty." };return duration
        } finally { runCatching { codec?.stop() };codec?.release();extractor.release() }
    }
    fun read(wav: File,start: Long,end: Long): FloatArray {
        require(start>=0 && end>start && end-start<=30000)
        return RandomAccessFile(wav,"r").use { file ->
            val first=start*16;val count=minOf((end-start)*16,(file.length()-44)/2-first).coerceAtLeast(0).toInt()
            file.seek(44+first*2);val bytes=ByteArray(count*2);file.readFully(bytes)
            val b=java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);FloatArray(count) { b.short/32768f }
        }
    }
}
