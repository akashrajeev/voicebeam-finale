package com.akashrajeev.voicebeam.separation

import android.content.Context
import android.net.Uri
import com.akashrajeev.voicebeam.core.ReferenceInterval
import com.akashrajeev.voicebeam.core.WavWriter
import com.akashrajeev.voicebeam.engine.SaveMode
import com.akashrajeev.voicebeam.record.MediaExporter
import com.akashrajeev.voicebeam.record.SessionMeta
import com.akashrajeev.voicebeam.record.SessionStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Atomic offline import: failures remove only this new folder, never the source video. */
object OfflineVideoImport {
    suspend fun run(context: Context, uri: Uri, start: Double, end: Double): SessionMeta = withContext(Dispatchers.IO) {
        val store = SessionStore(context)
        val (id,dir) = store.newSessionDir()
        try {
            require(dir.usableSpace > 550L*1024*1024) { "Keep at least 550 MB free for offline import" }
            val original = File(dir,"original.mp4")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open selected video" }
                original.outputStream().use { out ->
                    val b=ByteArray(65536); var total=0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n=input.read(b); if(n<0) break
                        total+=n; require(total<=256L*1024*1024) { "Choose a video under 256 MB" }
                        out.write(b,0,n)
                    }
                }
            }
            val samples=VideoAudioDecoder.decode(original)
            coroutineContext.ensureActive()
            val reference=ReferenceInterval.slice(samples,start,end)
            val raw=File(dir,"raw.wav")
            WavWriter(raw,16000).use { it.write(samples) }
            val clean=File(dir,"clean.wav")
            OfflineSpeakerBeam.extract(context,raw,reference,clean)
            coroutineContext.ensureActive()
            val videoTmp=File(dir,"video.tmp.mp4")
            MediaExporter.muxVideoWithWav(original,clean,videoTmp)
            require(videoTmp.length()>0 && videoTmp.renameTo(File(dir,"video.mp4"))) { "Cannot save isolated video" }
            coroutineContext.ensureActive()
            val meta=SessionMeta(id,"Offline isolated video",System.currentTimeMillis(),samples.size*1000L/16000,SaveMode.AUDIO_VIDEO,"none",dir)
            File(dir,"offline-reference.txt").writeText("Offline SpeakerBeam. Target-alone reference: $start to $end seconds. Original retained. Not live separation. Extraction quality is not guaranteed.\n")
            store.writeMeta(meta, emptyList())
            meta
        } catch(t: Throwable) { dir.deleteRecursively(); throw t }
    }
}
