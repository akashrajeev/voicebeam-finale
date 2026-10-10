package com.akashrajeev.voicebeam.footage

import android.content.Intent
import android.widget.VideoView
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
import androidx.compose.ui.viewinterop.AndroidView
import com.akashrajeev.voicebeam.Screen
import com.akashrajeev.voicebeam.VoiceBeamApp
import com.akashrajeev.voicebeam.recall.RecallConversation
import com.akashrajeev.voicebeam.ui.BottomNav

private val Bg=Color(0xFF0C1415)
private val Panel=Color(0xFF182629)
private val Ink=Color(0xFFF0EADD)
private val Mint=Color(0xFF3DDBB0)
private val Sub=Color(0xFFACC6BD)

@Composable
fun FootageScreen(onNavigate: (Screen)->Unit) {
    val context=LocalContext.current;val app=context.applicationContext as VoiceBeamApp
    val repo=app.footage;val state by repo.state.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf("home") }
    var video by remember { mutableStateOf<VideoView?>(null) }
    var replayEnd by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf("") }
    var threshold by rememberSaveable { mutableStateOf(0.3f) }
    var maxGroups by rememberSaveable { mutableStateOf(6) }
    var deletion by remember { mutableStateOf(false) }
    val clip=state.clips.find { it.id==selected }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) { repo.import(uri);page="home" }
    }
    DisposableEffect(Unit) { onDispose { video?.stopPlayback() } }
    LaunchedEffect(replayEnd,video) {
        while(replayEnd!=null) {
            val player=video
            if(player!=null && player.currentPosition>=replayEnd!!) { player.pause();replayEnd=null }
            kotlinx.coroutines.delay(80)
        }
    }
    fun play(start: Long=0,end: Long?=null) { video?.let { it.seekTo(start.toInt());it.start();replayEnd=end }?:run { error="Open this clip's preview to replay" } }
    androidx.activity.compose.BackHandler(page!="home") { page="home";selected=null;video?.pause() }
    if(deletion && clip!=null) AlertDialog(onDismissRequest={deletion=false},title={Text("Delete this saved footage?")},
        text={Text("Remove the local video copy, decoded audio, audio groups and transcript. Your original file on the phone stays unchanged.")},
        confirmButton={TextButton(onClick={repo.delete(clip.id);deletion=false;selected=null;page="home"}) { Text("Delete copy") }},
        dismissButton={TextButton(onClick={deletion=false}) { Text("Keep") }})
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(top=20.dp,bottom=24.dp)) {
            item {
                if(page!="home") TextButton(onClick={page="home";selected=null;video?.pause()}) { Text("‹ Footage",color=Mint) }
                Text("VOICEBEAM · ${com.akashrajeev.voicebeam.BuildConfig.VERSION_NAME}",color=Sub,fontSize=12.sp)
                Text(when(page) { "voices"->"Choose a voice";"compare"->"Review matched moments";"transcript"->"Read and replay";else->"Footage" },color=Ink,fontSize=32.sp,fontWeight=FontWeight.Bold)
                Text(if(page=="home") "Find the voice that matters." else clip?.title?:"Saved footage",color=Sub)
                Text(state.message,color=Mint,fontSize=12.sp)
                if(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth(),color=Mint);TextButton(onClick={repo.cancel()}) { Text("Pause processing") } }
                if(error.isNotBlank()) Text(error,color=Ink)
            }
            if(page=="home") {
                item { FootagePanel {
                    Text("Start with a video",color=Ink,fontSize=22.sp,fontWeight=FontWeight.Bold)
                    Text("CCTV, dashcam or a recording. Listen to audio groups, then review matched moments.",color=Sub)
                    Button(onClick={importer.launch(arrayOf("video/*"))},enabled=!state.busy&&!app.recall.state.value.recording) { Text("Import video") }
                    Text("Local processing · audio track required · lab limit 15 min / 1 GB",color=Sub,fontSize=12.sp)
                    Text("Audio groups are suggestions, not identified people. Original kept unchanged.",color=Sub,fontSize=12.sp)
                } }
                item { Text("RECENT FOOTAGE",color=Ink,fontSize=13.sp,fontWeight=FontWeight.Bold) }
                if(state.clips.isEmpty()) item { Text("Your imported videos will appear here.",color=Sub) }
                items(state.clips,key={it.id}) { saved -> FootagePanel(Modifier.clickable { selected=saved.id;page="voices" }) {
                    Text(saved.title,color=Ink,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                    Text("${RecallConversation.clock(saved.duration)} · ${saved.groups().count { it.id>=0 }} audio groups · ${saved.status}",color=Mint,fontSize=12.sp)
                    Text("Listen before choosing ›",color=Sub)
                } }
            } else if(clip!=null) {
                item {
                    AndroidView(factory={ ctx -> VideoView(ctx).apply {
                        video=this;setVideoPath(clip.video)
                        setOnPreparedListener { it.setVolume(1f,1f);seekTo(1) }
                        setOnErrorListener { _,_,_ -> error="Video preview unavailable on this device. The local original remains saved.";true }
                    } },update={ player -> if(video!==player) video=player },modifier=Modifier.fillMaxWidth().height(190.dp))
                    Text("Original video · ${RecallConversation.clock(clip.duration)}",color=Sub,fontSize=12.sp)
                    Row { TextButton(onClick={play()}) { Text("▶ Original") };TextButton(onClick={video?.pause();replayEnd=null}) { Text("Pause playback") } }
                }
                if(page=="voices") {
                    item {
                        Text("${clip.groups().count { it.id>=0 }} audio groups suggested",color=Ink,fontWeight=FontWeight.Bold)
                        Text("Voice labels come from sound, not faces. One person may appear in several groups; mixed speech may stay together.",color=Sub,fontSize=12.sp)
                    }
                    items(clip.groups(),key={it.id}) { group ->
                        var name by rememberSaveable(clip.id,group.id) { mutableStateOf(group.name) }
                        var editing by rememberSaveable(clip.id,group.id) { mutableStateOf(false) }
                        FootagePanel {
                            val loudest=clip.groups().firstOrNull { it.id>=0 }?.id==group.id
                            Text(group.name+if(loudest) " · loudest sample" else "",color=Ink,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                            Text("${RecallConversation.clock(group.speechMs)} matched audio · first ${RecallConversation.clock(group.windows.firstOrNull()?.start?:0)}",color=Sub,fontSize=12.sp)
                            Row {
                                TextButton(onClick={group.windows.maxByOrNull { it.end-it.start }?.let { play(it.start,minOf(it.end,it.start+5000)) }}) { Text("▶ Listen sample") }
                                TextButton(onClick={repo.pick(clip.id,group.id)},enabled=!state.busy) { Text(if(clip.selected==group.id) "✓ Selected" else "Pick this voice") }
                            }
                            TextButton(onClick={editing=!editing}) { Text("Rename / Merge") }
                            if(editing) {
                                OutlinedTextField(name,{name=it},label={Text("Audio group name")},modifier=Modifier.fillMaxWidth())
                                TextButton(onClick={repo.rename(clip.id,group.id,name);editing=false}) { Text("Save name") }
                                clip.groups().filter { it.id!=group.id }.forEach { other ->
                                    TextButton(onClick={repo.merge(clip.id,group.id,other.id);editing=false},enabled=!state.busy) { Text("Merge into ${other.name}") }
                                }
                                Text("Correct a matched moment",color=Sub,fontSize=12.sp)
                                group.windows.forEach { moment ->
                                    TextButton(onClick={play(moment.start,moment.end)}) { Text("▶ ${RecallConversation.clock(moment.start)} - ${RecallConversation.clock(moment.end)}") }
                                    TextButton(onClick={repo.moveWindow(clip.id,moment.start,group.id,null)},enabled=!state.busy) { Text("Move to a new audio group") }
                                }
                            }
                        }
                    }
                    item {
                        if(clip.selected!=null) Button(onClick={page="compare"},modifier=Modifier.fillMaxWidth()) { Text("Review ${clip.groups().find { it.id==clip.selected }?.name?:"selected audio"}") }
                        FootagePanel {
                            Text("Adjust audio grouping",color=Ink,fontWeight=FontWeight.Bold)
                            Text("Cosine similarity ${"%.2f".format(threshold)} · experimental, not a confidence score",color=Sub,fontSize=12.sp)
                            Slider(threshold,{threshold=it},valueRange=0.1f..0.9f)
                            Row { TextButton(onClick={maxGroups=maxOf(2,maxGroups-1)}) { Text("−") };Text("Max $maxGroups groups",color=Sub);TextButton(onClick={maxGroups=minOf(12,maxGroups+1)}) { Text("+") } }
                            Button(onClick={repo.analyzeAgain(clip.id,threshold,maxGroups)},enabled=!state.busy) { Text("Find groups again") }
                            Text("Reanalysis clears names/transcripts whose group boundaries change. Unmatched windows stay Uncertain.",color=Sub,fontSize=12.sp)
                        }
                        TextButton(onClick={deletion=true},enabled=!state.busy) { Text("Delete saved copy") }
                    }
                }
                if(page=="compare" || page=="transcript") {
                    val group=clip.selected
                    item {
                        TextButton(onClick={page="voices"}) { Text("Change voice") }
                        Text("${clip.groups().find { it.id==group }?.name?:"Selected audio"} · original matched moments",color=Mint)
                        Text("This selects time ranges, not an isolated voice. Other voices in mixed moments remain. Replay before relying on words.",color=Sub,fontSize=12.sp)
                        if(group!=null) Button(onClick={repo.transcribe(clip.id,group);page="transcript"},enabled=!state.busy) { Text("Transcribe matched moments with Gemma") }
                        if(!com.akashrajeev.voicebeam.recall.RecallModelFiles.ready(context)) TextButton(onClick={onNavigate(Screen.RECALL)}) { Text("Set up Recall's Gemma models first") }
                    }
                    if(page=="compare" && group!=null) items(FootageTimeline.ranges(clip.windows,group)) { range -> FootagePanel {
                        Text("${RecallConversation.clock(range.first)} - ${RecallConversation.clock(range.second)}",color=Mint)
                        TextButton(onClick={play(range.first,range.second)}) { Text("▶ Matched moment") }
                    } }
                    if(page=="transcript") {
                        val lines=clip.lines.filter { it.group==group }.sortedBy { it.start }
                        if(lines.isEmpty()) item { Text("Tap Transcribe to create words from the selected group's original audio moments.",color=Sub) }
                        items(lines,key={"${it.group}-${it.start}"}) { line -> FootagePanel {
                            Text("${RecallConversation.clock(line.start)} - ${RecallConversation.clock(line.end)}",color=Mint,fontSize=12.sp)
                            Text(if(line.status=="ready") line.text else if(line.status=="no_clear_speech") "No clear spoken words detected" else line.text,color=Ink,fontSize=18.sp)
                            Text(if(line.status=="ready") "Audio-group assignment · verify against original" else "${line.status} · original kept",color=Sub,fontSize=12.sp)
                            TextButton(onClick={play(line.start,line.end)}) { Text("▶ Replay original moment") }
                        } }
                        item { TextButton(onClick={
                            val body=clip.title+"\nAudio-group suggestions, not identified people. Original replay recommended.\n"+lines.filter { it.status=="ready" }.joinToString("\n") { "[${RecallConversation.clock(it.start)} - ${RecallConversation.clock(it.end)}] ${it.text}" }
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,body),"Share transcript for review"))
                        }) { Text("Share transcript") } }
                    }
                }
            }
        }
        BottomNav(Screen.FOOTAGE) { video?.pause();onNavigate(it) }
    }
}
@Composable
private fun FootagePanel(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=Panel) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
