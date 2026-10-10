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
    val modelsReady: Boolean = false, val busy: Boolean = false, val answers: List<RecallSegment> = emptyList())

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
        refresh();process();return id
    }
    @Synchronized fun process() {
        if(worker?.isActive==true || !RecallModelFiles.ready(context)) return
        worker=scope.launch {
            refresh("Processing on this phone",true)
            var completed=false
            try {
                inference.withLock { models.initialize() }
                while(true) {
                    val next=store.nextQueued()?:break
                    inference.withLock {
                        models.initialize()
                        try {
                            val text=if(next.status=="transcribed") next.text else models.transcribe(next.path)
                            // Save ASR before any secondary operation. Never lose a successful transcript.
                            store.update(next.id,text=text,status="transcribed")
                            val ready=next.copy(text=text,status="transcribed")
                            if(text.isNotBlank()) {
                                val vector=models.embedding(text)
                                store.update(next.id,vector=vector)
                                val notes=models.notes(ready)
                                store.update(next.id,notes=notes)
                            }
                            store.update(next.id,status="ready");refresh()
                        } catch(t: Exception) {
                            store.update(next.id,status=if(store.segments().find { it.id==next.id }?.text?.isNotBlank()==true) "needs_index" else "retry")
                            refresh("Clip ${next.id}: ${t.message?:"retry needed"}")
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
    fun ask(question: String, session: Long? = null) = scope.launch {
        if(question.isBlank()) return@launch
        synchronized(this@RecallRepository) { _state.value=_state.value.copy(answers=emptyList()) }
        refresh("Finding the source",true)
        try {
            val answers=inference.withLock {
                models.initialize()
                val query=models.embedding(question,true)
                val sources=store.segments(session).filter { it.text.isNotBlank() && it.vector!=null }
                    .sortedByDescending { RecallGrounding.cosine(query,it.vector!!) }.take(6)
                if(sources.isEmpty()) emptyList() else models.answer(question,sources)
            }
            synchronized(this@RecallRepository) { _state.value=_state.value.copy(answers=answers) }
            refresh(if(answers.isEmpty()) "Record a relevant conversation to find the answer" else "Answers from your transcript",false)
        } catch(t: Exception) { refresh(t.message?:"Try your question again",false) }
    }
    fun rename(session: Long, title: String) = scope.launch { store.rename(session,title);refresh() }
    fun nameSpeaker(id: Long, speaker: String) = scope.launch { store.update(id,speaker=speaker.trim().take(80));refresh() }
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
