package com.akashrajeev.voicebeam.recall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.akashrajeev.voicebeam.Screen
import com.akashrajeev.voicebeam.VoiceBeamApp
import com.akashrajeev.voicebeam.ui.BottomNav
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray

private val RecallBg=Color(0xFF0C1415)
private val RecallCard=Color(0xFF182629)
private val Sand=Color(0xFFF0EADD)
private val Mint=Color(0xFF3DDBB0)

@Composable
fun RecallScreen(onNavigate: (Screen)->Unit) {
    val context=LocalContext.current
    val repo=(context.applicationContext as VoiceBeamApp).recall
    val state by repo.state.collectAsState()
    var page by rememberSaveable { mutableStateOf("home") }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var question by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }

    var consent by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf(false) }
    var deleteDay by remember { mutableStateOf(false) }
    var dayOffset by rememberSaveable { mutableStateOf(0) }
    val day=remember(dayOffset) { java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY,0);set(java.util.Calendar.MINUTE,0);set(java.util.Calendar.SECOND,0);set(java.util.Calendar.MILLISECOND,0)
        add(java.util.Calendar.DAY_OF_YEAR,dayOffset)
    } }
    val dayStart=day.timeInMillis
    val dayEnd=(day.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR,1) }.timeInMillis
    val visibleSessions=state.sessions.filter { it.start>=dayStart && it.start<dayEnd }
    val daySegments=state.segments.filter { clip -> visibleSessions.any { it.id==clip.session } }
    var modelSpec by remember { mutableStateOf(RecallModelFiles.gemma) }
    val player=remember { MediaPlayer() }
    var playbackError by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { player.release() } }
    fun replay(segment: RecallSegment) {
        try { player.reset();player.setDataSource(segment.path);player.prepare();player.start();playbackError="" }
        catch(t: Exception) { playbackError=t.message?:"Audio replay needs retry" }
    }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if(grants[Manifest.permission.RECORD_AUDIO]==true) {
            ContextCompat.startForegroundService(context,Intent(context,RecallRecorderService::class.java).setAction(RecallRecorderService.START))
        }
    }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) repo.install(modelSpec,uri)
    }
    fun start() {
        val needed=mutableListOf(Manifest.permission.RECORD_AUDIO)
        if(Build.VERSION.SDK_INT>=33) needed+=Manifest.permission.POST_NOTIFICATIONS
        if(needed.all { ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED }) {
            ContextCompat.startForegroundService(context,Intent(context,RecallRecorderService::class.java).setAction(RecallRecorderService.START))
        } else permissions.launch(needed.toTypedArray())
    }
    androidx.activity.compose.BackHandler(page!="home") { page="home";selected=null }
    if(consent) AlertDialog(onDismissRequest={consent=false},title={Text("Start Recall")},
        text={Text("Record only when everyone agrees. Audio and transcripts stay on this phone. Pause anytime from the screen or notification.")},
        confirmButton={TextButton(onClick={consent=false;start()}) { Text("Everyone agrees. Start") }},
        dismissButton={TextButton(onClick={consent=false}) { Text("Cancel") }})
    if(deletion && selected!=null) AlertDialog(onDismissRequest={deletion=false},title={Text("Delete this conversation?")},
        text={Text("Remove the audio, transcript, notes and search data from this phone.")},
        confirmButton={TextButton(onClick={repo.delete(selected!!);deletion=false;selected=null;page="home"}) { Text("Delete") }},
        dismissButton={TextButton(onClick={deletion=false}) { Text("Keep") }})
    if(deleteDay) AlertDialog(onDismissRequest={deleteDay=false},title={Text("Delete this day's conversations?")},
        text={Text("Delete the selected day's audio, transcripts, notes and search data from this phone.")},
        confirmButton={TextButton(onClick={repo.deleteDay(dayStart,dayEnd);deleteDay=false}) { Text("Delete day") }},
        dismissButton={TextButton(onClick={deleteDay=false}) { Text("Keep") }})
    Column(Modifier.fillMaxSize().background(RecallBg).statusBarsPadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(top=20.dp,bottom=24.dp)) {
            item {
                if(page!="home") TextButton(onClick={page="home";selected=null}) { Text("‹ Your day",color=Mint) }
                Text("VOICEBEAM",color=Color(0xFF9FB4AE),fontSize=12.sp,letterSpacing=2.sp)
                Text(when(page) { "ask"->"Ask your day";"manage"->"Local models & privacy";"detail"->state.sessions.find { it.id==selected }?.title?:"Conversation notes";else->"Recall" },
                    color=Sand,fontSize=32.sp,fontWeight=FontWeight.Bold)
                Text(if(page=="ask") "Answers with the moment behind them." else "Your conversations, remembered.",color=Color(0xFF9FB4AE),fontSize=14.sp)
            }
            item {
                Text(state.message,color=Mint,fontSize=12.sp)
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Mint)
                if(playbackError.isNotEmpty()) Text(playbackError,color=Sand)
            }
            if(page=="home") {
                item {
                    RecallPanel {
                        Text(if(state.recording) "● Recording your day" else "Remember your next conversation",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Sand)
                        if(state.recording) Text("Elapsed ${RecallConversation.clock(state.recordingDuration)} · continues until Pause or a device interruption",color=Mint)
                        Text("Speech becomes notes. You stay present.",color=Color(0xFFACC6BD))
                        Button(onClick={if(state.recording) context.startService(Intent(context,RecallRecorderService::class.java).setAction(RecallRecorderService.STOP)) else consent=true},enabled=state.modelsReady) {
                            Text(if(state.recording) "Pause" else "Start recording")
                        }
                        if(!state.modelsReady) TextButton(onClick={page="manage"}) { Text("Set up local models") }
                    }
                }
                item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick={page="ask";selected=null}) { Text("✧ Ask your day") }
                    TextButton(onClick={page="manage"}) { Text("Manage") }
                } }
                item { Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick={dayOffset=0}) { Text("Today") }
                    TextButton(onClick={dayOffset=-1}) { Text("Yesterday") }
                    TextButton(onClick={page="all"}) { Text("All days") }
                } }
                item { Text("YOUR DAY",color=Sand,fontSize=13.sp,fontWeight=FontWeight.Bold) }
                if(visibleSessions.isEmpty()) item { RecallPanel { Text("Start a conversation to build your timeline.",color=Sand) } }
                items(visibleSessions,key={it.id}) { session ->
                    val clips=state.segments.filter { it.session==session.id }
                    RecallPanel(Modifier.clickable { selected=session.id;title=session.title;page="detail" }) {
                        Text(time(session.start),color=Mint,fontSize=12.sp)
                        Text(session.title,color=Sand,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                        Text(clips.firstOrNull { it.text.isNotBlank() }?.text?.take(150)?:"${clips.size} audio moments saved",color=Color(0xFFACC6BD))
                        Text("${RecallConversation.clock(maxOf(session.duration,RecallConversation.duration(clips)))} recorded · ${clips.size} replay anchors ›",color=Color(0xFF8AABA0),fontSize=12.sp)
                    }
                }
                item {
                    Text("Stored on this phone",color=Color(0xFF8AABA0),fontSize=12.sp)
                    val recap=daySegments.flatMap { clip ->
                        val notes=runCatching { JSONArray(clip.notes) }.getOrDefault(JSONArray())
                        (0 until notes.length()).map { notes.getJSONObject(it).optString("quote") }
                    }.filter { it.isNotBlank() }.distinct().take(6)
                    if(recap.isNotEmpty()) RecallPanel {
                        Text("DAILY RECAP · SOURCE EXTRACTS",color=Mint,fontSize=12.sp)
                        recap.forEach { Text(it,color=Sand) }
                    }
                    if(state.segments.any { it.status in listOf("retry","needs_index","review") }) TextButton(onClick={repo.retry()}) { Text("Retry processing") }
                    if(state.recording && state.segments.any { it.text.isNotBlank() }) {
                        RecallPanel { Text("CATCH-UP",color=Mint,fontSize=12.sp)
                            state.segments.filter { it.text.isNotBlank() }.takeLast(3).forEach { Text(it.text,color=Sand) }
                        }
                    }
                }
            }
            if(page=="all") {
                items(state.sessions,key={it.id}) { session -> RecallPanel(Modifier.clickable { selected=session.id;title=session.title;page="detail" }) {
                    Text("${time(session.start)} · ${RecallConversation.clock(maxOf(session.duration,RecallConversation.duration(state.segments.filter { it.session==session.id })))}",color=Mint);Text(session.title,color=Sand,fontSize=20.sp)
                } }
            }
            if(page=="detail") {
                item {
                    OutlinedTextField(title,{title=it},label={Text("Conversation name")},modifier=Modifier.fillMaxWidth())
                    Row { TextButton(onClick={selected?.let { repo.rename(it,title) }}) { Text("Save name") }
                        TextButton(onClick={page="ask"}) { Text("Ask this conversation") }
                        TextButton(onClick={deletion=true},enabled=!state.recording) { Text("Delete") }
                    }
                    TextButton(onClick={
                        val text=RecallConversation.transcript(state.segments.filter { it.session==selected }).joinToString("\n\n") { "[${RecallConversation.clock(it.source.start)}, source ${it.source.id}] ${it.text}" }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"Share conversation transcript"))
                    }) { Text("Share transcript") }
                }
                val clips=state.segments.filter { it.session==selected }
                val conversation=RecallConversation.transcript(clips)
                item {
                    val saved=state.sessions.find { it.id==selected }?.duration?:0L
                    val live=if(state.recording && state.recordingSession==selected) state.recordingDuration else 0L
                    Text("Recorded ${RecallConversation.clock(maxOf(saved,live,RecallConversation.duration(clips)))}",color=Mint)
                    Text("One continuous recording. Replay anchors are 25-second windows, not different speakers.",color=Color(0xFFACC6BD),fontSize=12.sp)
                    Text("CONVERSATION TRANSCRIPT",color=Sand,fontWeight=FontWeight.Bold)
                    if(conversation.isEmpty()) Text("Audio saved. Transcript is still processing or held for review.",color=Sand)
                }
                if(conversation.isNotEmpty()) item { RecallPanel {
                    conversation.forEach { entry ->
                        TextButton(onClick={replay(entry.source)}) { Text("▶ ${RecallConversation.clock(entry.source.start)} · source ${entry.source.id}") }
                        if(entry.source.speaker.isNotBlank()) Text(entry.source.speaker,color=Mint)
                        Text(entry.text,color=Sand)
                    }
                } }
                item {
                    Text("KEY POINTS · SOURCE EXTRACTS",color=Sand,fontWeight=FontWeight.Bold)
                    val points=clips.flatMap { clip ->
                        val notes=runCatching { JSONArray(clip.notes) }.getOrDefault(JSONArray())
                        if(notes.length()==0 && clip.text.isNotBlank()) RecallConversation.keyPoints(clip.text).map { clip to it }
                        else (0 until notes.length()).mapNotNull { i -> notes.optJSONObject(i)?.optString("quote")?.takeIf { it.isNotBlank() }?.let { clip to it } }
                    }.distinctBy { it.second }
                    points.forEach { (clip,quote) -> Text(quote,color=Sand);TextButton(onClick={replay(clip)}) { Text("▶ Source ${clip.id}") } }
                    if(points.isEmpty()) Text("Key points appear after speech processing.",color=Sand)
                    Text("AUDIO REPLAY & PROCESSING",color=Sand,fontWeight=FontWeight.Bold)
                }
                items(clips,key={"audio-${it.id}"}) { clip ->
                    var speaker by rememberSaveable(clip.id) { mutableStateOf(clip.speaker) }
                    var notesOpen by rememberSaveable(clip.id) { mutableStateOf(false) }
                    var transcriptOpen by rememberSaveable(clip.id) { mutableStateOf(false) }
                    var speakerOpen by rememberSaveable(clip.id) { mutableStateOf(false) }
                    RecallPanel {
                        Text("SOURCE ${clip.id} · ${clip.start/1000}s · ${clip.duration/1000}s",color=Mint,fontSize=12.sp)
                        if(clip.status in listOf("queued","transcribed")) {
                            val position=state.segments.filter { it.status in listOf("queued","transcribed") }.indexOfFirst { it.id==clip.id }+1
                            Text("Queue position $position · audio safely saved",color=Mint,fontSize=12.sp)
                        }
                        if(clip.status=="review") Text("Transcript held for review · replay or retry the original",color=Mint,fontSize=12.sp)
                        if(clip.status=="quiet") Text("No clear speech detected · original audio kept",color=Mint,fontSize=12.sp)
                        if(clip.status in listOf("retry","needs_index","quiet","review")) TextButton(onClick={repo.retry()}) { Text("Retry this saved audio") }
                        TextButton(onClick={replay(clip)}) { Text("▶ Replay original moment") }
                        if(clip.processingMs>0) Text("Processing time: ${clip.processingMs/1000}s · ${clip.status}",color=Color(0xFF8AABA0),fontSize=12.sp)
                        val notes=runCatching { JSONArray(clip.notes) }.getOrDefault(JSONArray())
                        TextButton(onClick={notesOpen=!notesOpen}) { Text("${if(notesOpen) "▾" else "▸"} Extracted notes (${notes.length()})") }
                        if(!notesOpen && notes.length()>0) Text(notes.getJSONObject(0).optString("quote").take(180),color=Sand)
                        if(notesOpen) for(i in 0 until notes.length()) {
                            val n=notes.getJSONObject(i)
                            Text(n.optString("kind").replace('_',' ').uppercase(),color=Mint,fontSize=11.sp)
                            Text(n.optString("quote"),color=Sand,fontWeight=FontWeight.SemiBold)
                            if(n.optString("kind")=="action") TextButton(onClick={
                                val intent=Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                                    .putExtra(android.provider.CalendarContract.Events.TITLE,n.optString("quote"))
                                    .putExtra(android.provider.CalendarContract.Events.DESCRIPTION,"From Recall source ${clip.id}. Review the date and time before saving.")
                                if(intent.resolveActivity(context.packageManager)!=null) context.startActivity(intent)
                                else playbackError="Open your calendar to add this reminder"
                            }) { Text("Add reminder") }
                        }
                        TextButton(onClick={transcriptOpen=!transcriptOpen}) { Text("${if(transcriptOpen) "▾" else "▸"} Raw window transcript") }
                        if(transcriptOpen) Text(clip.text.ifBlank { "Audio saved · ${clip.status}" },color=Sand)
                        if(clip.speaker.isNotBlank()) Text(clip.speaker,color=Mint)
                        if(clip.text.isNotBlank()) TextButton(onClick={speakerOpen=!speakerOpen}) { Text("${if(speakerOpen) "▾" else "▸"} Speaker name") }
                        if(speakerOpen && clip.text.isNotBlank()) {
                            OutlinedTextField(speaker,{speaker=it},label={Text("Name this speaker")},modifier=Modifier.fillMaxWidth())
                            TextButton(onClick={repo.nameSpeaker(clip.id,speaker);speakerOpen=false}) { Text("Save speaker name") }
                        }
                    }
                }
            }
            if(page=="ask") {
                item {
                    OutlinedTextField(question,{question=it},label={Text("Ask a question")},modifier=Modifier.fillMaxWidth())
                    Button(onClick={repo.ask(question,selected,if(selected==null) dayStart else null,if(selected==null) dayEnd else null)},enabled=state.modelsReady&&!state.asking&&question.isNotBlank()) { Text("Find answer") }
                    Text(if(selected==null) "${if(dayOffset==0) "Today" else "Yesterday"} conversations" else "This conversation",color=Mint,fontSize=12.sp)
                }
                items(state.answers) { answer -> RecallPanel {
                    Text(answer.text,color=Sand,fontSize=20.sp)
                    Text(if(answer.generated) "Generated from transcript · verify evidence below" else "Transcript extract · generation unavailable",color=Mint,fontSize=12.sp)
                    answer.citations.forEach { citation ->
                        Text("Source ${citation.source.id}: ${citation.quote}",color=Color(0xFFACC6BD),fontSize=13.sp)
                        TextButton(onClick={replay(citation.source)}) { Text("▶ ${RecallConversation.clock(citation.source.start)} · Replay source ${citation.source.id}") }
                    }
                } }
            }
            if(page=="manage") {
                item { RecallPanel {
                    Text("Gemma 4 E4B + EmbeddingGemma 2",color=Sand,fontWeight=FontWeight.Bold)
                    Text("3.82 GB total. Download once over Wi-Fi. Models are verified before installation.",color=Color(0xFFACC6BD))
                    Button(onClick={repo.install()},enabled=!state.busy) { Text(if(state.modelsReady) "Verify installed models" else "Download local models") }
                    TextButton(onClick={modelSpec=RecallModelFiles.gemma;importer.launch(arrayOf("*/*"))},enabled=!state.busy) { Text("Import Gemma .litertlm file") }
                    TextButton(onClick={modelSpec=RecallModelFiles.embedding;importer.launch(arrayOf("*/*"))},enabled=!state.busy) { Text("Import EmbeddingGemma file") }
                } }
                item { RecallPanel {
                    Text("Your recordings, your control",color=Sand,fontWeight=FontWeight.Bold)
                    Text("Start only with consent. Pause stops the microphone. Delete a conversation from its notes screen to remove audio, transcript and search data. Android backup is disabled.",color=Color(0xFFACC6BD))
                    TextButton(onClick={repo.retry()},enabled=!state.busy) { Text("Retry queued clips") }
                    TextButton(onClick={deleteDay=true},enabled=!state.recording && !state.busy) { Text("Delete ${if(dayOffset==0) "today" else "yesterday"}'s conversations") }
                } }
            }
        }
        BottomNav(Screen.RECALL) { destination ->
            if(destination == Screen.FOCUS) {
                context.startService(Intent(context,RecallRecorderService::class.java).setAction(RecallRecorderService.STOP))
                repo.releaseModels()
            }
            onNavigate(destination)
        }
    }
}

@Composable
private fun RecallPanel(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=RecallCard) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
private fun time(ms: Long)=SimpleDateFormat("EEE, h:mm a",Locale.getDefault()).format(Date(ms))
