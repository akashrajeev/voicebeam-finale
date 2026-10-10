package com.akashrajeev.voicebeam.reminders
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akashrajeev.voicebeam.Screen
import com.akashrajeev.voicebeam.VoiceBeamApp
import java.time.ZoneId
private val Paper=Color(0xFFFFFBF1)
private val Blue=Color(0xFF153CB5)
private val Ink=Color(0xFF101216)
@Composable fun ReminderScreen(onNavigate: (Screen)->Unit) {
    val context=LocalContext.current;val app=context.applicationContext as VoiceBeamApp;val repo=app.reminders;val state by repo.state.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) };var cues by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") };var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var pendingWatch by remember { mutableStateOf<String?>(null) }
    val microphone=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) runCatching { context.startForegroundService(Intent(context,ReminderCaptureService::class.java).setAction(ReminderCaptureService.START).putExtra("watch",pendingWatch)) }.onFailure { error=it.message.orEmpty() }
        else error="Allow the microphone to record instructions"
    }
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { repo.refresh("Check alert access, then confirm your reminder") }
    fun capture(watch: String?=null) {
        pendingWatch=watch
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) microphone.launch(Manifest.permission.RECORD_AUDIO)
        else runCatching { context.startForegroundService(Intent(context,ReminderCaptureService::class.java).setAction(ReminderCaptureService.START).putExtra("watch",watch)) }.onFailure { error=it.message.orEmpty() }
    }
    fun play(path: String) { runCatching { player?.release();player=MediaPlayer().apply { setDataSource(path);prepare();start() } }.onFailure { error="Original audio is unavailable" } }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    androidx.activity.compose.BackHandler(state.recording) { context.startService(Intent(context,ReminderCaptureService::class.java).setAction(ReminderCaptureService.STOP));repo.refresh("Listening stopped. Wait for the recording to finish.") }
    androidx.activity.compose.BackHandler(!state.recording && (selected!=null || cues)) { selected=null;cues=false }
    MaterialTheme(colorScheme=lightColorScheme(primary=Blue,onPrimary=Color.White,background=Paper,surface=Color.White,onSurface=Ink,onBackground=Ink)) {
        Column(Modifier.fillMaxSize().background(Paper).statusBarsPadding()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text("VOICEBEAM · ${com.akashrajeev.voicebeam.BuildConfig.VERSION_NAME}",fontSize=14.sp,color=Ink)
                Text(if(cues) "Alert settings" else if(state.recording) "Listening" else if(selected!=null) "Did I understand?" else "Reminders",fontSize=36.sp,fontWeight=FontWeight.Bold,color=Ink)
                Text(state.message,fontSize=20.sp,color=Ink)
                if(error.isNotBlank()) Text(error,fontSize=20.sp,color=Color(0xFF8B180C))
                if(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth());Text("Please wait. Your original voice is saved.",fontSize=22.sp) }
                if(state.recording) {
                    Text(if(state.watching!=null) "Listening for your turn number" else "Hold the phone near the person speaking.",fontSize=26.sp)
                    Text("Saved only on this phone · up to 25 seconds per instruction",fontSize=20.sp)
                    BigButton("Stop listening") { context.startService(Intent(context,ReminderCaptureService::class.java).setAction(ReminderCaptureService.STOP)) }
                } else if(cues) {
                    val prefs=context.getSharedPreferences("reminder-cues",0)
                    for((key,label,default) in listOf(Triple("vibration","Repeating vibration",true),Triple("speech","Speak the reminder offline",true),Triple("flash","Slow screen flash",false))) {
                        var enabled by remember(key) { mutableStateOf(prefs.getBoolean(key,default)) }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(label,fontSize=22.sp,modifier=Modifier.weight(1f));Switch(enabled,{ enabled=it;prefs.edit().putBoolean(key,it).apply() }) }
                    }
                    Text("Repeat intervals mean a fixed number of minutes, not selected daily clock times. A repeat is scheduled after acknowledgement; no missed-dose advice. Vibration pauses after 10 minutes; the reminder stays pending until acknowledged. Screen flash works while the alert is open. Flash can be uncomfortable; leave it off if sensitive.",fontSize=20.sp)
                    Text("Phone off, force-stop, disabled alerts or battery restrictions can prevent a cue. Test alerts on this phone.",fontSize=20.sp)
                    Text(ReminderScheduler(context).readiness()?:"Notifications and exact alarms allowed",fontSize=22.sp)
                    BigButton("Allow notifications") { if(Build.VERSION.SDK_INT>=33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)) }
                    BigButton("Allow exact alarms") { if(Build.VERSION.SDK_INT>=31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,android.net.Uri.parse("package:${context.packageName}"))) }
                    if(Build.VERSION.SDK_INT>=34) {
                        Text(if(context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) "Full-screen alerts allowed" else "Full-screen access needed; notification may show instead",fontSize=20.sp)
                        BigButton("Allow full-screen alerts") { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,android.net.Uri.parse("package:${context.packageName}"))) }
                    }
                    BigButton("Done") { cues=false;ReminderScheduler(context).restore();repo.refresh() }
                } else {
                    val card=state.cards.find { it.id==selected }
                    if(card!=null) {
                        ReminderConfirm(card,repo,play={play(card.audio)},onDone={selected=null},onError={error=it})
                    } else {
                        if(state.cards.none { it.status !in setOf("acknowledged","cancelled") }) {
                            Text("Nothing to remember right now",fontSize=30.sp,fontWeight=FontWeight.Bold)
                            Text("When someone tells you something important, tap below. I will write it down and show it to you.",fontSize=24.sp)
                        }
                        BigButton("Listen for instructions",enabled=!state.busy) { capture() }
                        if(!com.akashrajeev.voicebeam.recall.RecallModelFiles.ready(context)) BigButton("Set up Gemma in Recall") { onNavigate(Screen.RECALL) }
                        TextButton(onClick={selected=repo.manual()},enabled=!state.busy) { Text("Add details myself",fontSize=22.sp) }
                        BigButton("Alert settings") { cues=true }
                        val active=state.cards.filter { it.status !in setOf("acknowledged","cancelled") }.sortedWith(compareBy<ReminderCard> { it.status!="ringing" }.thenBy { it.due?:Long.MAX_VALUE })
                        Text("TODAY / NEXT",fontSize=24.sp,fontWeight=FontWeight.Bold)
                        active.forEach { c ->
                            Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(2.dp,Ink),color=Color.White) {
                                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Text(c.what.ifBlank { "Review saved recording" },fontSize=28.sp,fontWeight=FontWeight.Bold)
                                    Text(if(c.due!=null) java.time.Instant.ofEpochMilli(c.due).atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")) else if(c.token.isNotBlank()) "Token ${c.token} · ${c.status}" else "Details to confirm",fontSize=22.sp)
                                    c.fields["bring"]?.let { Text("Bring ${it.value}",fontSize=22.sp) }
                                    if(c.status in setOf("review","needs_permission","missed")) BigButton("Review and confirm") { selected=c.id }
                                    else if(c.status=="ringing") BigButton("See alert") { context.startActivity(Intent(context,ReminderAlertActivity::class.java).putExtra("id",c.id)) }
                                    else if(c.status=="turn_ready") { Text("English numeric tokens only in this lab. Slow processing can miss calls; keep checking the display.",fontSize=20.sp)
                                    BigButton("Listen for my turn",enabled=!state.busy) { capture(c.id) } }
                                    BigButton("Hear original voice") { play(c.audio) }
                                    TextButton(onClick={repo.cancel(c.id)}) { Text("Cancel reminder",fontSize=20.sp) }
                                }
                            }
                        }
                        val previous=state.cards.filter { it.status=="acknowledged" }
                        if(previous.isNotEmpty()) { Text("SEEN",fontSize=24.sp,fontWeight=FontWeight.Bold);previous.take(10).forEach { Text(it.what,fontSize=22.sp) } }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(vertical=8.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
                for((destination,label) in listOf(Screen.FOCUS to "Listen",Screen.RECALL to "Recall",Screen.REMINDERS to "Reminders",Screen.SETTINGS to "Settings")) {
                    TextButton(onClick={onNavigate(destination)},enabled=!state.recording,modifier=Modifier.weight(1f).heightIn(min=64.dp),colors=ButtonDefaults.textButtonColors(contentColor=Ink,containerColor=if(destination==Screen.REMINDERS) Color(0xFFFFD600) else Color.White)) { Text(label,fontSize=14.sp,fontWeight=FontWeight.Bold) }
                }
            }
        }
    }
}
@Composable private fun BigButton(label: String,enabled: Boolean=true,onClick: ()->Unit) {
    Button(onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=72.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) { Text(label,fontSize=24.sp,fontWeight=FontWeight.Bold) }
}
@Composable private fun ReminderConfirm(card: ReminderCard,repo: ReminderRepository,play: ()->Unit,onDone: ()->Unit,onError: (String)->Unit) {
    var values by remember(card.id) { mutableStateOf(card.fields.mapValues { it.value.value }) }
    var editing by remember(card.id) { mutableStateOf(false) }
    var date by remember(card.id) { mutableStateOf(card.due?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.orEmpty()) };var time by remember(card.id) { mutableStateOf(card.due?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) }.orEmpty()) }
    var repeat by remember(card.id) { mutableStateOf(card.repeatMinutes?.toString().orEmpty()) };var token by remember(card.id) { mutableStateOf(card.fields["token"]?.value?.takeIf { it.matches(Regex("[0-9]+")) }.orEmpty()) }
    var counter by remember(card.id) { mutableStateOf(card.fields["counter"]?.value?.takeIf { it.matches(Regex("[0-9]+")) }.orEmpty()) }
    Text("Nothing is set until you say Yes.",fontSize=22.sp)
    if(card.transcript.isNotBlank()) { Text("What the phone heard",fontSize=20.sp);Text(card.transcript,fontSize=24.sp) }
    for(key in listOf("what","when","bring","place","contact","repeat").filter { editing || it in setOf("what","when") || values[it]?.isNotBlank()==true }) {
        Text(key.replaceFirstChar { it.uppercase() },fontSize=20.sp,fontWeight=FontWeight.Bold)
        if(editing || key=="what" && values[key].isNullOrBlank()) OutlinedTextField(values[key].orEmpty(),{ values=values+(key to it) },label={Text(key)},modifier=Modifier.fillMaxWidth(),textStyle=androidx.compose.ui.text.TextStyle(fontSize=24.sp))
        else Text(values[key]?.ifBlank { "Not said" }?:"Not said",fontSize=26.sp)
    }
    if(card.audio.isNotBlank()) BigButton("Hear the original voice",onClick=play)
    BigButton(if(editing) "Finish corrections" else "Correct") { editing=!editing }
    Text("Choose the exact alert time, or a turn number. Words such as 'after food' do not set a clock time.",fontSize=22.sp)
    val context=LocalContext.current
    BigButton(if(date.isBlank()) "Choose date" else date) {
        val now=java.time.LocalDate.now()
        android.app.DatePickerDialog(context,{ _,y,m,d -> date=java.time.LocalDate.of(y,m+1,d).toString() },now.year,now.monthValue-1,now.dayOfMonth).show()
    }
    BigButton(if(time.isBlank()) "Choose time" else time) { android.app.TimePickerDialog(context,{ _,h,m -> time="%02d:%02d".format(h,m) },12,0,true).show() }
    OutlinedTextField(repeat,{repeat=it},label={Text("Repeat every N minutes (optional)")},textStyle=androidx.compose.ui.text.TextStyle(fontSize=24.sp),modifier=Modifier.fillMaxWidth())
    TextButton(onClick={date="";time="";repeat=""}) { Text("Clear alert time",fontSize=22.sp) }
    Text("For My Turn",fontSize=24.sp,fontWeight=FontWeight.Bold)
    OutlinedTextField(token,{token=it},label={Text("Your numeric token (optional)")},modifier=Modifier.fillMaxWidth(),textStyle=androidx.compose.ui.text.TextStyle(fontSize=24.sp))
    OutlinedTextField(counter,{counter=it},label={Text("Counter number (optional)")},modifier=Modifier.fillMaxWidth(),textStyle=androidx.compose.ui.text.TextStyle(fontSize=24.sp))
    BigButton("Yes, confirm") {
        try {
            require((date.isBlank() && time.isBlank()) || (date.isNotBlank() && time.isNotBlank())) { "Choose both date and time" }
            val at=if(date.isBlank()) null else ReminderGrounding.localTime(date,time,ZoneId.systemDefault())
            val every=repeat.trim().takeIf { it.isNotEmpty() }?.let { it.toLongOrNull()?:error("Enter repeat minutes as a number") }
            val error=repo.confirm(card.id,values,at,every,token.trim(),counter.trim());if(error==null) onDone() else onError(error)
        } catch(t: Exception) { onError(t.message?:"Please check the date and time") }
    }
    BigButton("Keep for later",onClick=onDone)
}
