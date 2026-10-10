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
    val recordingSession: Long? = null, val recordingDuration: Long = 0L, val askMessage: String = "", val recap: List<RecallAnswer> = emptyList(),
    val recapMessage: String = "", val recapping: Boolean = false)

class RecallRepository(private val context: Context) {
    val store = RecallStore(context)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val inference=Mutex()
    private val maintenance=Mutex()
    private val models=RecallModels(context)
    private val _state=MutableStateFlow(RecallUiState(modelsReady=RecallModelFiles.ready(context)))
    val state=_state.asStateFlow()
    private var worker: Job? = null
    private var recapWorker: Job? = null
    private var recapRequest: Pair<Long,Long>? = null
    private data class RecapCache(val signature: String, val answers: List<RecallAnswer>, val message: String)
    private val recapCache=mutableMapOf<Long,RecapCache>()
    private fun recapSources(start: Long, end: Long): List<RecallSegment> {
        val ids=store.sessions().filter { it.start>=start && it.start<end }.map { it.id }.toSet()
        return store.segments().filter { it.session in ids && RecallPromptGuard.usable(it) }
    }
    private fun decodeMiniRecap(clip: RecallSegment): List<RecallAnswer>? {
        val saved=store.sourceRecap(clip)?:return null
        return runCatching {
            val array=org.json.JSONArray(saved)
            (0 until array.length()).mapNotNull { index ->
                val item=array.getJSONObject(index);val quotes=item.getJSONArray("quotes")
                val citations=(0 until quotes.length()).map { RecallCitation(clip,quotes.getString(it)) }
                if(citations.isEmpty() || citations.any { !RecallGrounding.isExactQuote(it.quote,clip.text) }) null
                else RecallAnswer(item.getString("text"),citations,item.optBoolean("generated"),item.optString("reason"))
            }
        }.getOrNull()
    }
    private fun saveMiniRecap(clip: RecallSegment, answers: List<RecallAnswer>) {
        val array=org.json.JSONArray()
        answers.forEach { answer -> array.put(org.json.JSONObject().put("text",answer.text).put("generated",answer.generated)
            .put("reason",answer.fallbackReason).put("quotes",org.json.JSONArray(answer.citations.map { it.quote }))) }
        store.saveSourceRecap(clip,array.toString())
    }
    private fun recapSignature(sources: List<RecallSegment>) = sources.joinToString("|") { "${it.id}:${it.text}:${it.notes}" }
    @Synchronized fun recap(start: Long, end: Long, force: Boolean = false) {
        if(force) recapCache.remove(start)
        recapRequest=start to end
        val sources=recapSources(start,end);val signature=recapSignature(sources)
        val cached=recapCache[start]?.takeIf { it.signature==signature }
        if(cached!=null) {
            _state.value=_state.value.copy(recap=cached.answers,recapMessage=cached.message,recapping=false);return
        }
        if(sources.isEmpty()) {
            _state.value=_state.value.copy(recap=emptyList(),recapMessage="No processed speech for this day yet",recapping=false);return
        }
        if(_state.value.recording || _state.value.busy) {
            _state.value=_state.value.copy(recap=emptyList(),recapMessage="Recap updates after Pause and queued speech processing",recapping=false);return
        }
        if(!RecallModelFiles.ready(context)) return
        if(recapWorker?.isActive==true) {
            if(force) _state.value=_state.value.copy(recapMessage="Previous recap is still finishing/cancelling. Retry after it returns.")
            return
        }
        _state.value=_state.value.copy(recap=emptyList(),recapMessage="Updating mini-summaries across ${sources.size} audio sources",recapping=true)
        recapWorker=scope.launch {
            val request=start to end
            val input=recapSources(start,end);val version=recapSignature(input)
            val groups=input.sortedBy { it.id }
            val started=android.os.SystemClock.elapsedRealtime();val deadline=started+120000
            val partial=groups.associate { it.id to decodeMiniRecap(it) }.toMutableMap()
            // Empty model results are not successful summaries; retry them in later runs.
            partial.keys.toList().forEach { id -> if(partial[id]?.isEmpty()==true) partial[id]=null }
            var completed=partial.values.count { it!=null }
            fun collected()=groups.flatMap { partial[it.id].orEmpty() }
            var answers=collected()
            synchronized(this@RecallRepository) { if(recapRequest==request) _state.value=_state.value.copy(recap=answers) }
            val stage=java.util.concurrent.atomic.AtomicReference("Waiting for local processing")
            val expired=java.util.concurrent.atomic.AtomicBoolean(false)
            val ownsInference=java.util.concurrent.atomic.AtomicBoolean(false)
            val watchdog=scope.launch {
                while(isActive) {
                    delay(1000)
                    val elapsed=android.os.SystemClock.elapsedRealtime()-started
                    synchronized(this@RecallRepository) {
                        if(recapRequest==request) _state.value=_state.value.copy(
                            recapMessage=if(elapsed>=120000) "Recap timed out after 2 minutes. Cancelling local generation; retry when it finishes." else "${stage.get()} · ${elapsed/1000}s · $completed/${groups.size} sources done",
                            recapping=elapsed<120000)
                    }
                    if(elapsed>=120000) { expired.set(true);if(ownsInference.get()) models.cancelActive("recap");break }
                }
            }
            try {
                for((index,clip) in groups.withIndex()) {
                    if(partial[clip.id]!=null) continue
                    if(_state.value.recording || _state.value.busy) error("Recap paused for recording/queued processing. Tap Retry recap after Pause.")
                    stage.set("Waiting for local model · source ${index+1}/${groups.size}")
                    val result=withTimeout(maxOf(1L,deadline-android.os.SystemClock.elapsedRealtime())) {
                        inference.withLock {
                            check(!expired.get()) { "Recap timed out. Tap Retry recap." }
                            stage.set("Summarizing source ${index+1}/${groups.size}")
                            ownsInference.set(true)
                            models.operation="recap"
                            try {
                                models.initialize()
                                models.miniSummary(clip,deadline)
                            } finally { models.operation="other";ownsInference.set(false) }
                        }
                    }
                    if(result.isNotEmpty()) { saveMiniRecap(clip,result);partial[clip.id]=result;completed++ }
                    answers=collected()
                    synchronized(this@RecallRepository) {
                        if(recapRequest==request) _state.value=_state.value.copy(recap=answers.toList(),recapMessage="Reviewed $completed/${groups.size} sources")
                    }
                    yield()
                }
                synchronized(this@RecallRepository) {
                    if(recapSignature(recapSources(start,end))!=version) {
                        if(recapRequest==request) _state.value=_state.value.copy(recap=emptyList(),recapMessage="Transcripts changed while summarizing. Tap Retry recap for the latest sources.",recapping=false)
                    } else {
                        val message="${input.map { it.session }.distinct().size} conversations · $completed/${input.size} sources reviewed" +
                            if(answers.isEmpty()) " · No supported recap returned. Tap Retry recap." else if(completed<groups.size) " · Some sources had no validated summary. Tap Retry recap." else " · Replay evidence to verify"
                        if(completed==groups.size) recapCache[start]=RecapCache(version,answers.toList(),message)
                        if(recapRequest==request) _state.value=_state.value.copy(recap=answers.toList(),recapMessage=message,recapping=false)
                    }
                }
            } catch(t: Exception) {
                synchronized(this@RecallRepository) {
                    if(recapRequest==request) _state.value=_state.value.copy(recap=answers.toList(),
                        recapMessage=(if(expired.get() || t is TimeoutCancellationException) "Recap timed out after 2 minutes" else t.message?:"Recap failed") + " · $completed/${groups.size} sources reviewed. Tap Retry recap.",recapping=false)
                }
            } finally {
                watchdog.cancel()
                synchronized(this@RecallRepository) {
                    recapWorker=null
                    if(recapRequest==request) _state.value=_state.value.copy(recapping=false)
                }
            }
        }
    }
    init { store.quarantinePromptLeaks();refresh(); process() }
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
                                if(RecallPromptGuard.contaminated(result)) {
                                    store.update(next.id,text=result,status="contaminated",notes="[]",processingMs=android.os.SystemClock.elapsedRealtime()-started)
                                    store.index(next.id,emptyList())
                                    refresh("Clip ${next.id}: instruction echo held for review; excluded from notes/search. Replay or retry.",true)
                                    return@withLock
                                }
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
            _state.value=_state.value.copy(answers=emptyList(),asking=true,askMessage="Finding source evidence")
        }
        try {
            val answers=inference.withLock {
                models.initialize()
                require(question.toByteArray().size<=600) { "Please shorten the question and try again" }
                val allowed=store.sessions().filter { session!=null && it.id==session || session==null && (dayStart==null || it.start>=dayStart) && (dayEnd==null || it.start<dayEnd) }.map { it.id }.toSet()
                val sources=if(RecallConversation.summaryQuestion(question)) store.segments(session).filter { it.session in allowed && RecallPromptGuard.usable(it) }
                    else store.search(models.embedding(question,true),session,allowed)
                if(sources.isEmpty()) emptyList() else models.answer(question,sources)
            }
            synchronized(this@RecallRepository) { _state.value=_state.value.copy(answers=answers,
                askMessage=if(answers.isEmpty()) "No supported answer found in the selected transcripts" else "Answer with source evidence. Replay to verify.") }
        } catch(t: Exception) { synchronized(this@RecallRepository) { _state.value=_state.value.copy(askMessage=t.message?:"Try your question again") } }
        finally { synchronized(this@RecallRepository) { _state.value=_state.value.copy(asking=false) } }
    }
    suspend fun instructionAudio(file: File,extract: Boolean=true): Pair<String,String> = inference.withLock {
        check(!_state.value.recording) { "Pause Recall first" }
        models.initialize()
        val samples=com.akashrajeev.voicebeam.core.WavWriter.read(file).first
        val speech=RecallSpeechGate(context).use { it.speechOnly(samples) }?:return@withLock "" to "[]"
        val filtered=File(context.cacheDir,"reminder-speech.wav")
        val text=try { com.akashrajeev.voicebeam.core.WavWriter(filtered,16000).use { it.write(speech) };models.transcribe(filtered.path) } finally { filtered.delete() }
        check(!RecallPromptGuard.contaminated(text) && !RecallTranscriptQuality.repeatedLoop(text)) { "Please replay and record the instruction again" }
        if(text.isBlank() || text=="NO_SPEECH") "" to "[]" else text to if(extract) models.reminderJson(text) else "[]"
    }
    suspend fun instructionText(text: String): String = inference.withLock { models.initialize();models.reminderJson(text) }
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
