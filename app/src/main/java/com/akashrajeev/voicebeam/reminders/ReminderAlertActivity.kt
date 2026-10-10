package com.akashrajeev.voicebeam.reminders
import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akashrajeev.voicebeam.VoiceBeamApp
class ReminderAlertActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(Build.VERSION.SDK_INT>=27) { setShowWhenLocked(true);setTurnScreenOn(true) }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val id=intent.getStringExtra("id")?:run { finish();return }
        (application as VoiceBeamApp).reminders.refresh()
        setContent { MaterialTheme { ReminderAlert(id) { finish() } } }
    }
}
@Composable fun ReminderAlert(id: String,close: ()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val app=context.applicationContext as VoiceBeamApp;val state by app.reminders.state.collectAsState()
    val card=app.reminders.store.get(id)
    // Read through the durable card on first render; a notification can launch before a flow refresh.
    val refreshed=state.cards.size
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var message by remember { mutableStateOf("") };var bright by remember { mutableStateOf(true) }
    val flash=context.getSharedPreferences("reminder-cues",0).getBoolean("flash",false)
    LaunchedEffect(flash) { while(flash) { kotlinx.coroutines.delay(1500);bright=!bright } }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    if(card==null || card.status!="ringing") { LaunchedEffect(Unit) { close() };return }
    val yellow=Color(0xFFFFD600)
    Column(Modifier.fillMaxSize().background(if(flash&&!bright) Color(0xFFFFF7CE) else yellow).systemBarsPadding().padding(22.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(24.dp)) {
        Text(if(card.token.isBlank()) "REMINDER" else "MY TURN",color=Color.Black,fontSize=24.sp,fontWeight=FontWeight.Bold)
        if(card.token.isNotBlank()) {
            Text("Your number was heard",fontSize=28.sp,color=Color.Black)
            Text(card.token,fontSize=86.sp,fontWeight=FontWeight.Bold,color=Color.Black)
            Text(if(card.counter.isBlank()) "Check the announcement" else "Counter ${card.counter}",fontSize=36.sp,color=Color.Black,fontWeight=FontWeight.Bold)
        } else {
            Text(card.what,fontSize=42.sp,fontWeight=FontWeight.Bold,color=Color.Black)
            card.fields["bring"]?.let { Text("Bring ${it.value}",fontSize=28.sp,color=Color.Black) }
            card.fields["place"]?.let { Text(it.value,fontSize=28.sp,color=Color.Black) }
            Text(card.due?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")) }.orEmpty(),fontSize=24.sp,color=Color.Black)
        }
        Text("Waiting for your acknowledgement",fontSize=20.sp,color=Color.Black)
        Button(onClick={ context.startService(Intent(context,ReminderAlertService::class.java).setAction(ReminderAlertService.ACK).putExtra("id",id));close() },
            modifier=Modifier.fillMaxWidth().heightIn(min=88.dp),colors=ButtonDefaults.buttonColors(containerColor=Color.Black,contentColor=yellow)) {
            Text(if(card.token.isBlank()) "OK, I see it" else "I am going",fontSize=30.sp,fontWeight=FontWeight.Bold)
        }
        Button(onClick={
            runCatching { player?.release();player=MediaPlayer().apply { setDataSource(card.alertAudio.ifBlank { card.audio });prepare();start() } }.onFailure { message="Original voice is unavailable" }
        },modifier=Modifier.fillMaxWidth().heightIn(min=64.dp),colors=ButtonDefaults.buttonColors(containerColor=Color.White,contentColor=Color.Black)) { Text("Hear the original voice",fontSize=22.sp) }
        if(card.token.isBlank()) TextButton(onClick={ context.startService(Intent(context,ReminderAlertService::class.java).setAction(ReminderAlertService.SNOOZE).putExtra("id",id));close() }) { Text("Snooze 10 minutes",fontSize=22.sp,color=Color.Black) }
        if(message.isNotBlank()) Text(message,fontSize=20.sp,color=Color.Black)
    }
}
