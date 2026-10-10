package com.akashrajeev.voicebeam.record

import android.content.Context
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.akashrajeev.voicebeam.core.CaptionSegment
import com.akashrajeev.voicebeam.core.Captions
import com.akashrajeev.voicebeam.core.WavWriter
import com.google.common.collect.ImmutableList
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Turns the raw recording files into the files the user keeps and shares. */
object MediaExporter {

    /** Encode a 16-bit mono wav into AAC inside an .m4a container. */
    fun wavToM4a(wav: File, out: File) {
        val (samples, sr) = WavWriter.read(wav)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val track = encodeAacInto(muxer, samples, sr, startMuxer = true)
        muxer.stop(); muxer.release()
        if (track < 0) throw IllegalStateException("AAC encode failed")
    }

    /** Copy the video track from [videoIn] and add [wav] as AAC audio. */
    fun muxVideoWithWav(videoIn: File, wav: File, out: File) {
        val (samples, sr) = WavWriter.read(wav)
        val ex = MediaExtractor().apply { setDataSource(videoIn.absolutePath) }
        var vIndex = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) { vIndex = i; break }
        }
        require(vIndex >= 0) { "no video track" }
        ex.selectTrack(vIndex)
        val vFormat = ex.getTrackFormat(vIndex)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(vFormat.getInteger(MediaFormat.KEY_ROTATION))
        val vTrack = muxer.addTrack(vFormat)
        // Encode audio first to learn its format, buffering encoded packets.
        val packets = mutableListOf<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
        val aFormat = encodeAac(samples, sr) { buf, info -> packets.add(Pair(buf, info)) }
        val aTrack = muxer.addTrack(aFormat)
        muxer.start()
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            info.set(0, size, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(vTrack, buf, info)
            ex.advance()
        }
        for ((b, i) in packets) muxer.writeSampleData(aTrack, b, i)
        muxer.stop(); muxer.release(); ex.release()
    }

    private fun encodeAacInto(muxer: MediaMuxer, samples: FloatArray, sr: Int, startMuxer: Boolean): Int {
        val packets = mutableListOf<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
        val fmt = encodeAac(samples, sr) { b, i -> packets.add(Pair(b, i)) }
        val t = muxer.addTrack(fmt)
        if (startMuxer) muxer.start()
        for ((b, i) in packets) muxer.writeSampleData(t, b, i)
        return t
    }

    /** Encodes PCM to AAC-LC; returns the output format, emits packets through [sink]. */
    private fun encodeAac(samples: FloatArray, sr: Int, sink: (ByteBuffer, MediaCodec.BufferInfo) -> Unit): MediaFormat {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        var outFormat: MediaFormat? = null
        var pos = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(8192)
        while (true) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx)!!
                    ib.clear()
                    val n = minOf((samples.size - pos), minOf(ib.capacity(), pcm.size) / 2)
                    if (n <= 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, pos * 1_000_000L / sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        for (k in 0 until n) {
                            val v = (samples[pos + k].coerceIn(-1f, 1f) * 32767f).toInt()
                            pcm[2 * k] = (v and 0xff).toByte(); pcm[2 * k + 1] = ((v shr 8) and 0xff).toByte()
                        }
                        ib.put(pcm, 0, n * 2)
                        codec.queueInputBuffer(inIdx, 0, n * 2, pos * 1_000_000L / sr, 0)
                        pos += n
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                outIdx >= 0 -> {
                    val ob = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val copy = ByteBuffer.allocate(info.size)
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        copy.put(ob); copy.flip()
                        val ci = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                        sink(copy, ci)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        codec.stop(); codec.release()
        return outFormat ?: format
    }

    /** Re-encode [input] with captions drawn on top. Runs Media3 Transformer on the main thread. */
    @OptIn(UnstableApi::class)
    suspend fun burnCaptions(context: Context, input: File, output: File, segments: List<CaptionSegment>) =
        suspendCancellableCoroutine<Unit> { cont ->
            val overlay = object : TextOverlay() {
                private val settings = OverlaySettings.Builder()
                    .setBackgroundFrameAnchor(0f, -0.78f)
                    .build()
                override fun getText(presentationTimeUs: Long): SpannableString {
                    val seg = Captions.activeAt(segments, presentationTimeUs / 1000)
                    val text = seg?.text?.let { if (it.length > 60) "…" + it.takeLast(60) else it } ?: " "
                    return SpannableString(" $text ").apply {
                        setSpan(ForegroundColorSpan(if (seg?.isTarget == false) Color.LTGRAY else Color.WHITE), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(BackgroundColorSpan(Color.argb(170, 0, 0, 0)), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(AbsoluteSizeSpan(44), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
            }
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setEffects(Effects(ImmutableList.of(), ImmutableList.of<androidx.media3.common.Effect>(OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(overlay)))))
                .build()
            Handler(Looper.getMainLooper()).post {
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                transformer.start(item, output.absolutePath)
                cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
            }
        }
}
