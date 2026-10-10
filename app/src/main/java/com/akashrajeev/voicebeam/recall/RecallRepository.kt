package com.akashrajeev.voicebeam.recall

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RecallUiState(val recording: Boolean = false, val message: String = "Ready to remember",
    val sessions: List<RecallSession> = emptyList(), val segments: List<RecallSegment> = emptyList(),
    val modelsReady: Boolean = false, val busy: Boolean = false, val asking: Boolean = false, val answers: List<RecallAnswer> = emptyList(),
    val recordingSession: Long? = null, val recordingDuration: Long = 0L)

class RecallRepository(private val context: Context) {
    val store = RecallStore(context)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val inference=Mutex()
    private val maintenance=Mutex()
    private val models=RecallModels(context)
    private val _state=MutableStateFlow(RecallUiState(modelsReady=RecallModelFiles.ready(context)))
    val state=_state.asStateFlow()
    private var worker: Job? = null
    init { refresh(); process() }
    @Synchronized fun refresh(message: String? = null, busy: Boolean? = null) {
        _state.value=_state.value.copy(sessions=store.sessions(),segments=store.segments(),
            modelsReady=RecallModelFiles.ready(context),message=message?:_state.value.message,busy=busy?:_state.value.busy)
    }
    @Synchronized fun recording(active: Boolean) { _state.value=_state.value.copy(recording=active) }
    @Synchronized fun recordingProgress(session: Long, duration: Long) {
        _state.value=_state.value.copy(recordingSession=session,recordingDuration=duration)
    }
    fun install(spec: RecallModelFiles.Spec? = null, uri: Uri? = null) = scope.launch {
        maintenance.withLock {
            refresh("Installing local models",true)
            try {
                inference.withLock {
                    models.close()
                    val specs=spec?.let { listOf(it) }?:listOf(RecallModelFiles.gemma,RecallModelFiles.embedding)
                    specs.forEach {
                        if(uri==null && RecallModelFiles.file(context,it).length()==it.size) {
                            refresh("Verifying ${it.name}",true);RecallModelFiles.verify(context,it)
                        } else RecallModelFiles.install(context,it,uri) { message -> refresh(message,true) }
                    }
                }
                refresh("Models installed. Ready for a phone test",false); process()
            } catch(t: Exception) { refresh(t.message?:"Model installation needs retry",false) }
        }
    }
    fun queued(session: Long, start: Long, samples: FloatArray): Long {
        val dir=File(context.filesDir,"recall-audio").apply { mkdirs() }
        val file=File(dir,"${session}-${start}-${System.nanoTime()}.wav")
        com.akashrajeev.voicebeam.core.WavWriter(file,16000).use { it.write(samples) }
        val id=store.add(session,start,samples.size*1000L/16000,file)
        if(RecallAudioEnergy.nearSilent(samples)) store.update(id,status="quiet")
        refresh();process();return id
    }
    @Synchronized fun process() {
        if(worker?.isActive==true || !RecallModelFiles.ready(context)) return
        worker=scope.launch {
            refresh("Loading local models",true)
            var completed=false
            try {
                inference.withLock { models.initialize() }
                while(true) {
                    val next=store.nextQueued()?:break
                    inference.withLock {
                        models.initialize()
                        val started=android.os.SystemClock.elapsedRealtime()
                        try {
                            val queued=store.segments().count { it.status in listOf("queued","transcribed") }
                            refresh("Transcribing clip ${next.id} · $queued queued",true)
                            val text=if(next.status=="transcribed") next.text else {
                                val samples=com.akashrajeev.voicebeam.core.WavWriter.read(File(next.path)).first
                                val speech=RecallSpeechGate(context).use { it.speechOnly(samples) }
                                if(speech==null) {
                                    store.update(next.id,status="quiet",processingMs=android.os.SystemClock.elapsedRealtime()-started)
                                    refresh("Clip ${next.id}: no clear speech detected; original audio kept",true)
                                    return@withLock
                                }
                                val filtered=File(context.cacheDir,"recall-speech-${next.id}.wav")
                                val result=try {
                                    com.akashrajeev.voicebeam.core.WavWriter(filtered,16000).use { it.write(speech) }
                                    models.transcribe(filtered.path)
                                } finally { filtered.delete() }
                                if(RecallTranscriptQuality.repeatedLoop(result)) {
                                    store.update(next.id,status="review",processingMs=android.os.SystemClock.elapsedRealtime()-started)
                                    refresh("Clip ${next.id}: repeated output held for review. Replay or retry the audio.",true)
                                    return@withLock
                                }
                                if(result.trim()=="NO_SPEECH") "" else result
                            }
                            // Save ASR before any secondary operation. Never lose a successful transcript.
                            store.update(next.id,text=text,status="transcribed")
                            val ready=next.copy(text=text,status="transcribed")
                            if(text.isNotBlank()) {
                                refresh("Indexing clip ${next.id} · $queued queued",true)
                                val slices=models.indexSlices(text)
                                store.index(next.id,slices)
                                refresh("Extracting notes · clip ${next.id} · $queued queued",true)
                                val notes=models.notes(ready)
                                store.update(next.id,notes=notes)
                            }
                            val elapsed=android.os.SystemClock.elapsedRealtime()-started
                            store.update(next.id,status="ready",processingMs=elapsed)
                            refresh("Clip ${next.id} processed in ${elapsed/1000}s",true)
                        } catch(t: Exception) {
                            store.update(next.id,status=if(store.segments().find { it.id==next.id }?.text?.isNotBlank()==true) "needs_index" else "retry")
                            refresh("Clip ${next.id} saved. Processing needs retry; later clips will continue.",true)
                        }
                    }
                }
                completed=true
                refresh("Saved on this phone",false)
            } catch(t: Exception) { refresh(t.message?:"Model loading needs retry",false) }
            finally {
                // Serialize clearing the worker with scheduling. A chunk may arrive after the final SELECT.
                synchronized(this@RecallRepository) {
                    worker=null
                    if(completed && store.nextQueued()!=null) process()
                }
            }
        }
    }
    fun retry() {
        scope.launch {
            inference.withLock {
                store.retryFailed()
            }
            process()
        }
    }
    fun ask(question: String, session: Long? = null, dayStart: Long? = null, dayEnd: Long? = null) = scope.launch {
        if(question.isBlank()) return@launch
        synchronized(this@RecallRepository) {
            if(_state.value.asking) return@launch
            _state.value=_state.value.copy(answers=emptyList(),asking=true)
        }
        refresh("Finding the source",true)
        try {
            val answers=inference.withLock {
                models.initialize()
                require(question.toByteArray().size<=600) { "Please shorten the question and try again" }
                val allowed=store.sessions().filter { session!=null && it.id==session || session==null && (dayStart==null || it.start>=dayStart) && (dayEnd==null || it.start<dayEnd) }.map { it.id }.toSet()
                val sources=if(RecallConversation.summaryQuestion(question)) store.segments(session).filter { it.session in allowed && it.text.isNotBlank() }
                    else store.search(models.embedding(question,true),session,allowed)
                if(sources.isEmpty()) emptyList() else models.answer(question,sources)
            }
            synchronized(this@RecallRepository) { _state.value=_state.value.copy(answers=answers) }
            refresh(if(answers.isEmpty()) "Record a relevant conversation to find the answer" else "Answer with source evidence. Replay to verify.",false)
        } catch(t: Exception) { refresh(t.message?:"Try your question again",false) }
        finally { synchronized(this@RecallRepository) { _state.value=_state.value.copy(asking=false) } }
    }
    fun rename(session: Long, title: String) = scope.launch { store.rename(session,title);refresh() }
    fun nameSpeaker(id: Long, speaker: String) = scope.launch {
        val name=speaker.trim().take(80)
        store.update(id,speaker=name)
        synchronized(this@RecallRepository) {
            _state.value=_state.value.copy(segments=_state.value.segments.map { if(it.id==id) it.copy(speaker=name) else it },message="Speaker name saved")
        }
        refresh("Speaker name saved")
    }
    fun delete(session: Long) = scope.launch {
        if(state.value.recording) { refresh("Pause recording to manage saved conversations");return@launch }
        inference.withLock {
            store.delete(session)
            synchronized(this@RecallRepository) { _state.value=_state.value.copy(answers=emptyList()) }
            refresh("Conversation deleted from this phone")
        }
    }
    fun deleteDay(dayStart: Long, dayEnd: Long) = scope.launch {
        if(state.value.recording) { refresh("Pause recording to manage saved conversations");return@launch }
        inference.withLock {
            store.sessions().filter { it.start>=dayStart && it.start<dayEnd }.forEach { store.delete(it.id) }
            synchronized(this@RecallRepository) { _state.value=_state.value.copy(answers=emptyList()) }
            refresh("Day deleted from this phone")
        }
    }
    fun releaseModels() = scope.launch { inference.withLock { models.close() } }
}
