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
                item { Text("YOUR DAY",color=Sand,fontSize=13.sp,fontWeight=FontWeight.Bold) }
                if(state.sessions.isEmpty()) item { RecallPanel { Text("Start a conversation to build your timeline.",color=Sand) } }
                items(state.sessions,key={it.id}) { session ->
                    val clips=state.segments.filter { it.session==session.id }
                    RecallPanel(Modifier.clickable { selected=session.id;title=session.title;page="detail" }) {
                        Text(time(session.start),color=Mint,fontSize=12.sp)
                        Text(session.title,color=Sand,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                        Text(clips.firstOrNull { it.text.isNotBlank() }?.text?.take(150)?:"${clips.size} audio moments saved",color=Color(0xFFACC6BD))
                        Text("${clips.size} moments · Tap for notes and replay ›",color=Color(0xFF8AABA0),fontSize=12.sp)
                    }
                }
                item {
                    Text("Stored on this phone",color=Color(0xFF8AABA0),fontSize=12.sp)
                    if(state.segments.any { it.status in listOf("retry","needs_index") }) TextButton(onClick={repo.retry()}) { Text("Retry processing") }
                    if(state.recording && state.segments.any { it.text.isNotBlank() }) {
                        RecallPanel { Text("CATCH-UP",color=Mint,fontSize=12.sp)
                            state.segments.filter { it.text.isNotBlank() }.takeLast(3).forEach { Text(it.text,color=Sand) }
                        }
                    }
                }
            }
            if(page=="detail") {
                item {
                    OutlinedTextField(title,{title=it},label={Text("Conversation name")},modifier=Modifier.fillMaxWidth())
                    Row { TextButton(onClick={selected?.let { repo.rename(it,title) }}) { Text("Save name") }
                        TextButton(onClick={page="ask"}) { Text("Ask this conversation") }
                        TextButton(onClick={deletion=true},enabled=!state.recording) { Text("Delete") }
                    }
                    TextButton(onClick={
                        val text=state.segments.filter { it.session==selected }.joinToString("\n\n") { "[Source ${it.id}, ${it.start/1000}s] ${it.text}" }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"Share conversation transcript"))
                    }) { Text("Share transcript") }
                }
                val clips=state.segments.filter { it.session==selected }
                items(clips,key={it.id}) { clip ->
                    var speaker by rememberSaveable(clip.id) { mutableStateOf(clip.speaker) }
                    RecallPanel {
                        Text("SOURCE ${clip.id} · ${clip.start/1000}s · ${clip.duration/1000}s",color=Mint,fontSize=12.sp)
                        val notes=runCatching { JSONArray(clip.notes) }.getOrDefault(JSONArray())
                        for(i in 0 until notes.length()) {
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
                        Text("TRANSCRIPT",color=Color(0xFF8AABA0),fontSize=11.sp)
                        Text(clip.text.ifBlank { "Audio saved · ${clip.status}" },color=Sand)
                        if(clip.speaker.isNotBlank()) Text(clip.speaker,color=Mint)
                        TextButton(onClick={replay(clip)}) { Text("▶ Replay original moment") }
                        OutlinedTextField(speaker,{speaker=it},label={Text("Name this speaker")},modifier=Modifier.fillMaxWidth())
                        TextButton(onClick={repo.nameSpeaker(clip.id,speaker);speaker=""}) { Text("Save speaker name") }
                    }
                }
            }
            if(page=="ask") {
                item {
                    OutlinedTextField(question,{question=it},label={Text("Ask a question")},modifier=Modifier.fillMaxWidth())
                    Button(onClick={repo.ask(question,selected)},enabled=state.modelsReady&&!state.busy&&question.isNotBlank()) { Text("Find answer") }
                    Text(if(selected==null) "All conversations" else "This conversation",color=Mint,fontSize=12.sp)
                }
                items(state.answers,key={it.id}) { source -> RecallPanel {
                    Text(source.text,color=Sand,fontSize=20.sp)
                    Text("Transcript extract · source ${source.id}",color=Mint,fontSize=12.sp)
                    TextButton(onClick={replay(source)}) { Text("▶ Replay source moment") }
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
