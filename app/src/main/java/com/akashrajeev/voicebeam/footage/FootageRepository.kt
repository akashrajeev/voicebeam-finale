package com.akashrajeev.voicebeam.footage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.akashrajeev.voicebeam.VoiceBeamApp
import com.akashrajeev.voicebeam.ml.NeuralVad
import com.akashrajeev.voicebeam.ml.VoicePrint
import com.akashrajeev.voicebeam.core.WavWriter
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FootageState(val clips: List<FootageClip> = emptyList(),val busy: Boolean=false,val message: String="Choose a video to start",val active: String?=null)
class FootageRepository(private val context: Context) {
    private val store=FootageStore(context)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val cancel=AtomicBoolean(false)
    private val _state=MutableStateFlow(FootageState(clips=store.clips()))
    val state=_state.asStateFlow()
    init {
        store.clips().filter { it.status in listOf("importing","analyzing","transcribing") }.forEach { store.save(it.copy(status="paused")) };refresh()
    }
    @Synchronized private fun refresh(message: String?=null,busy: Boolean?=null,active: String?=null) {
        _state.value=_state.value.copy(clips=store.clips(),message=message?:_state.value.message,busy=busy?:_state.value.busy,active=active?:_state.value.active)
    }
    @Synchronized private fun begin(): Boolean {
        if(_state.value.busy) return false
        cancel.set(false);_state.value=_state.value.copy(busy=true);return true
    }
    fun cancel() { cancel.set(true);refresh("Stopping after the current local operation; original video kept") }
    fun import(uri: Uri) {
        if(!begin()) return
        scope.launch {
            val id=UUID.randomUUID().toString();val dir=File(store.root,id).apply { mkdirs() }
            val video=File(dir,"original.video");val audio=File(dir,"audio.wav");var clip: FootageClip?=null
            try {
                check(!(context.applicationContext as VoiceBeamApp).recall.state.value.recording) { "Pause Recall before importing footage" }
                val title=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null }?:"Imported video"
                clip=FootageClip(id,title,video.path,audio.path,0,"importing");store.save(clip);refresh("Copying video locally",true,id)
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open that video. Choose it again." }
                    video.outputStream().buffered().use { output ->
                        val buffer=ByteArray(256*1024);var total=0L
                        while(true) { check(!cancel.get()) { "Import cancelled" };val n=input.read(buffer);if(n<0) break
                            total+=n;check(total<=1_000_000_000L) { "Lab imports support files up to 1 GB" }
                            check(dir.usableSpace>n+30_000_000) { "Free storage and try again" };output.write(buffer,0,n)
                        }
                    }
                }
                val duration=FootageAudio.decode(video,audio,{cancel.get()}) { ms -> refresh("Decoding audio · ${ms/1000}s",true,id) }
                clip=clip.copy(duration=duration,status="analyzing");store.save(clip)
                analyze(clip,0.3f,6)
            } catch(t: Exception) { clip?.let { saved -> store.save((store.clips().find { it.id==saved.id }?:saved).copy(status="needs_retry")) };refresh(t.message?:"Import needs retry",false,id) }
            finally { refresh(busy=false) }
        }
    }
    fun analyzeAgain(id: String,threshold: Float,maxGroups: Int) {
        if(!begin()) return
        scope.launch { try {
            val clip=store.clips().find { it.id==id }?:error("Clip is no longer saved")
            require(File(clip.audio).exists()) { "Import this video again; decoded audio is unavailable" }
            analyze(clip,threshold,maxGroups)
        } catch(t: Exception) { refresh(t.message?:"Voice analysis needs retry",false) } finally { refresh(busy=false) } }
    }
    private fun analyze(clip: FootageClip,threshold: Float,maxGroups: Int) {
        require(threshold in 0.1f..0.9f && maxGroups in 2..12)
        refresh("Finding speech in ${clip.title}",true,clip.id);store.save(clip.copy(status="analyzing"))
        val speech=mutableListOf<Pair<Long,Long>>()
        val vad=NeuralVad(context.assets)
        try {
            var start: Long?=null;var lastVoice=0L;var pos=0L
            while(pos<clip.duration) {
                check(!cancel.get()) { "Analysis cancelled. Audio remains saved." }
                val end=minOf(clip.duration,pos+3200);val samples=FootageAudio.read(File(clip.audio),pos,end)
                var offset=0
                while(offset<samples.size) {
                    val frame=samples.copyOfRange(offset,minOf(offset+512,samples.size));val at=pos+offset*1000L/16000
                    if(vad.isVoice(frame)) { if(start==null) start=maxOf(0,at-160);lastVoice=at+32 }
                    else if(start!=null && at-lastVoice>=300) { if(lastVoice-start!!>=400) speech+=start!! to minOf(clip.duration,lastVoice+160);start=null }
                    offset+=512
                }
                pos=end;refresh("Finding speech · ${pos/1000}/${clip.duration/1000}s",true,clip.id)
            }
            start?.let { if(lastVoice-it>=400) speech+=it to minOf(clip.duration,lastVoice+160) }
        } finally { vad.release() }
        val vectors=mutableListOf<FloatArray?>();val windows=mutableListOf<FootageWindow>();val print=VoicePrint(context.assets)
        try {
            for((start,end) in speech) {
                var pos=start
                while(pos<end) {
                    check(!cancel.get()) { "Analysis cancelled. Audio remains saved." }
                    val next=if(end-pos<=4000) end else pos+3000
                    val samples=FootageAudio.read(File(clip.audio),pos,next)
                    val vector=if(next-pos>=1000) print.embed(samples) else null
                    vectors+=vector
                    val level=kotlin.math.sqrt(samples.sumOf { it.toDouble()*it }/maxOf(1,samples.size)).toFloat()
                    windows+=FootageWindow(pos,next,-1,level,0f);pos=next
                    refresh("Comparing voice samples · ${windows.size} audio windows",true,clip.id)
                }
            }
        } finally { print.release() }
        refresh("Grouping the full clip · ${windows.size} audio windows",true,clip.id)
        val assignments=FootageOfflineGroups.assign(vectors,threshold,maxGroups)
        val grouped=windows.mapIndexed { i,w -> w.copy(group=assignments[i].first,similarity=assignments[i].second) }
        val next=clip.copy(windows=grouped,lines=emptyList(),names=emptyMap(),selected=null,status="ready")
        val suggested=next.groups().firstOrNull { it.id>=0 }?.id
        store.save(next.copy(selected=suggested));refresh(if(windows.isEmpty()) "No clear speech found. Original video kept for replay." else "Audio groups ready. Listen before choosing or merging.",false,clip.id)
    }
    fun pick(id: String,group: Int) { if(state.value.busy) return;scope.launch { val clip=store.clips().find { it.id==id }?:return@launch;store.save(clip.copy(selected=group));refresh("Voice selected") } }
    fun rename(id: String,group: Int,name: String) { if(state.value.busy) return;scope.launch { val clip=store.clips().find { it.id==id }?:return@launch;store.save(clip.copy(names=clip.names+(group to name.trim().take(60))));refresh("Audio group name saved") } }
    fun merge(id: String,from: Int,into: Int) { if(state.value.busy) return;scope.launch { val clip=store.clips().find { it.id==id }?:return@launch;store.save(FootageTimeline.merge(clip,from,into));refresh("Audio groups merged. Transcribe again to update changed attribution.") } }
    fun moveWindow(id: String,start: Long,from: Int,into: Int?) {
        if(state.value.busy) return
        scope.launch {
            val clip=store.clips().find { it.id==id }?:return@launch
            val destination=into?:((clip.windows.maxOfOrNull { it.group }?:-1)+1)
            store.save(clip.copy(windows=clip.windows.map { if(it.group==from && it.start==start) it.copy(group=destination) else it },
                lines=clip.lines.filter { it.group!=from && it.group!=destination }))
            refresh("Moment reassigned. Transcribe changed groups again.")
        }
    }
    fun transcribe(id: String,group: Int) {
        if(!begin()) return
        scope.launch {
            var clip=store.clips().find { it.id==id }
            try {
                var current=requireNotNull(clip);val recall=(context.applicationContext as VoiceBeamApp).recall
                check(!recall.state.value.recording) { "Pause Recall before transcribing footage" }
                current=current.copy(status="transcribing");clip=current;store.save(current)
                val ranges=FootageTimeline.ranges(current.windows,group)
                for((index,range) in ranges.withIndex()) {
                    check(!cancel.get()) { "Transcription paused. Completed moments kept." }
                    if(current.lines.any { it.group==group && it.start==range.first && it.end==range.second && it.status=="ready" }) continue
                    refresh("Transcribing matched audio · ${index+1}/${ranges.size}",true,id)
                    val wav=File(context.cacheDir,"footage-$id-$index.wav")
                    val line=try {
                        WavWriter(wav,16000).use { it.write(FootageAudio.read(File(current.audio),range.first,range.second)) }
                        val text=recall.transcribeImported(wav)
                        FootageLine(range.first,range.second,group,text,if(text.isBlank()) "no_clear_speech" else "ready")
                    } catch(t: Exception) { FootageLine(range.first,range.second,group,t.message?:"Replay and retry","review") }
                    finally { wav.delete() }
                    current=current.copy(lines=current.lines.filterNot { it.group==group && it.start==range.first && it.end==range.second }+line)
                    clip=current;store.save(current);refresh(active=id)
                }
                current=current.copy(status="ready");clip=current;store.save(current);refresh("Matched moments transcribed. Replay the original to check words.",false,id)
            } catch(t: Exception) { clip?.let { store.save(it.copy(status="paused")) };refresh(t.message?:"Transcription needs retry",false,id) }
            finally { refresh(busy=false) }
        }
    }
    fun delete(id: String) { if(state.value.busy) return;scope.launch { store.delete(id);refresh("Footage deleted from this phone") } }
}
