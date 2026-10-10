package com.akashrajeev.voicebeam.separation

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.nio.ByteOrder

/** Bounded local-file decode. Does not use the microphone or face pipeline. */
object VideoAudioDecoder {
    fun decode(file: File): FloatArray {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            var audio = -1; var video = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/") && audio < 0) audio = i
                if (mime.startsWith("video/") && video < 0) video = i
            }
            require(audio >= 0 && video >= 0) { "Choose a video with an audio track" }
            val vf = ex.getTrackFormat(video)
            val duration = vf.getLong(MediaFormat.KEY_DURATION)
            require(duration in 1000000L..120000000L) { "Choose a video between 1 and 120 seconds" }
            ex.selectTrack(video)
            val videoStart = ex.sampleTime
            ex.unselectTrack(video); ex.selectTrack(audio)
            require(kotlin.math.abs(ex.sampleTime - videoStart) <= 50000L) { "This video's audio/video offset is unsupported" }
            val format = ex.getTrackFormat(audio)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            var sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(sr in 8000..96000 && channels in 1..8) { "Unsupported audio format" }
            var pcm = android.media.AudioFormat.ENCODING_PCM_16BIT
            var inputEnd = false; var outputEnd = false
            var count = 0
            var samples = FloatArray(minOf(sr * 122, 48000 * 122))
            val info = MediaCodec.BufferInfo()
            val start = SystemClock.elapsedRealtime()
            while (!outputEnd) {
                require(SystemClock.elapsedRealtime() - start < 45000) { "Video decode timed out" }
                if (!inputEnd) {
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val b = decoder.getInputBuffer(index)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) { decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnd = true }
                        else { decoder.queueInputBuffer(index,0,n,ex.sampleTime,0); ex.advance() }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info,10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat
                    val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    require(count == 0) { "Audio sample rate changed" }
                    sr = newRate; channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(sr in 8000..96000 && channels in 1..8) { "Unsupported audio format" }
                    pcm = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else android.media.AudioFormat.ENCODING_PCM_16BIT
                    require(pcm == android.media.AudioFormat.ENCODING_PCM_16BIT || pcm == android.media.AudioFormat.ENCODING_PCM_FLOAT) { "Unsupported PCM format" }
                    samples = FloatArray(sr * 122)
                } else if (index >= 0) {
                    try {
                        val b = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val bytes = if (pcm == android.media.AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        while (b.remaining() >= channels * bytes) {
                            require(count < samples.size) { "Video audio exceeds 120 seconds" }
                            var v = 0f
                            repeat(channels) { v += if (bytes == 4) b.float else b.short / 32768f }
                            samples[count++] = v / channels
                        }
                        outputEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(index,false) }
                }
            }
            require(count > sr && (0 until count).all { samples[it].isFinite() }) { "Empty or invalid audio" }
            val outputSize = (duration * 16000 / 1000000).toInt()
            require(kotlin.math.abs(count.toDouble()/sr - duration/1000000.0) <= .1) { "Audio/video lengths differ; trim your video first" }
            // Band-limited arbitrary-rate conversion, not live processing.
            return FloatArray(outputSize) { j ->
                val x = j.toDouble() * sr / 16000
                val center = x.toInt(); val cutoff = minOf(1.0, 16000.0/sr) * .9
                var sum = 0.0; var weight = 0.0
                for (k in center-24..center+24) if (k in 0 until count) {
                    val t = x-k
                    val w = if (kotlin.math.abs(t)<1e-9) cutoff else kotlin.math.sin(Math.PI*cutoff*t)/(Math.PI*t)
                    val h = w * (.5+.5*kotlin.math.cos(Math.PI*t/25))
                    sum += samples[k]*h; weight += h
                }
                if (weight == 0.0) 0f else (sum/weight).toFloat()
            }
        } finally { try { codec?.stop() } catch (_: Exception) {} ; codec?.release(); ex.release() }
    }
}
