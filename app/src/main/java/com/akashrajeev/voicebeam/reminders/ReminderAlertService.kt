package com.akashrajeev.voicebeam.reminders
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.akashrajeev.voicebeam.VoiceBeamApp
import java.util.Locale
class ReminderAlertService : Service() {
    private val handler=Handler(Looper.getMainLooper())
    private var tts: TextToSpeech?=null
    private var current: String?=null
    private var wakeLock: PowerManager.WakeLock?=null
    private val repo get()=(application as VoiceBeamApp).reminders
    private fun vibrator()=if(Build.VERSION.SDK_INT>=31) getSystemService(VibratorManager::class.java).defaultVibrator else getSystemService(Vibrator::class.java)
    private fun pending(id: String,action: String): PendingIntent=PendingIntent.getService(this,0,
        Intent(this,javaClass).setAction(action).setData(android.net.Uri.parse("voicebeam://alert/$id/$action")).putExtra("id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val id=intent?.getStringExtra("id")?:return START_NOT_STICKY
        if(intent.action in setOf(ACK,SNOOZE)) { repo.acknowledge(id,if(intent.action==SNOOZE) 10 else null);getSystemService(NotificationManager::class.java).cancel((id.hashCode() and 0x7fffffff).coerceAtLeast(1));next();return START_NOT_STICKY }
        val card=repo.store.get(id)?:return START_NOT_STICKY
        if(card.status!="ringing") return START_NOT_STICKY
        if(current!=null && current!=id && repo.store.get(current!!)?.status=="ringing") { repo.refresh("Another reminder is queued behind the current alert");return START_NOT_STICKY }
        current=id
        wakeLock?.let { if(it.isHeld) it.release() }
        wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VoiceBeam:ReminderCue").apply { acquire(600000) }
        val manager=getSystemService(NotificationManager::class.java)
        val channel=NotificationChannel("reminder-alert","Confirmed reminders",NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true);vibrationPattern=longArrayOf(0,600,350,600);lockscreenVisibility=android.app.Notification.VISIBILITY_PRIVATE
        };manager.createNotificationChannel(channel)
        val open=PendingIntent.getActivity(this,0,Intent(this,ReminderAlertActivity::class.java).setData(android.net.Uri.parse("voicebeam://open/$id")).putExtra("id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note=NotificationCompat.Builder(this,"reminder-alert").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if(card.token.isBlank()) card.what else "Your number ${card.token} was heard")
            .setContentText(card.fields["bring"]?.value?:"Tap to see your reminder and hear the original voice")
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open).setFullScreenIntent(open,true).setOngoing(true)
            .addAction(0,"OK, I see it",pending(id,ACK)).addAction(0,"Snooze 10 min",pending(id,SNOOZE)).build()
        if(Build.VERSION.SDK_INT>=34) startForeground((id.hashCode() and 0x7fffffff).coerceAtLeast(1),note,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground((id.hashCode() and 0x7fffffff).coerceAtLeast(1),note)
        vibrator().cancel();if(getSharedPreferences("reminder-cues",0).getBoolean("vibration",true)) vibrator().vibrate(VibrationEffect.createWaveform(longArrayOf(0,600,350,600,900),0))
        handler.removeCallbacksAndMessages(null)
        // A bounded cue loop avoids an unattended battery drain. Notification persists until acknowledged.
        handler.postDelayed({ vibrator().cancel();tts?.stop();repo.refresh("Reminder still waiting for acknowledgement; repeating cues paused after 10 minutes") },600000)
        tts?.shutdown()
        if(getSharedPreferences("reminder-cues",0).getBoolean("speech",true)) tts=TextToSpeech(this) { result ->
            if(result==TextToSpeech.SUCCESS) { val voice=tts?.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language==Locale.getDefault().language };if(voice!=null) { tts?.voice=voice;val readback=if(card.token.isBlank()) card.text() else "Your number ${card.token}. ${card.alertText}";tts?.speak(readback,TextToSpeech.QUEUE_FLUSH,null,id)
                val started=SystemClock.elapsedRealtime()
                val speakAgain=object : Runnable { override fun run() { if(current==id && SystemClock.elapsedRealtime()-started<600000) { tts?.speak(readback,TextToSpeech.QUEUE_FLUSH,null,id);handler.postDelayed(this,60000) } } }
                handler.postDelayed(speakAgain,60000) } else repo.refresh("Install an offline voice for spoken alerts") }
        }
        repo.refresh();return START_NOT_STICKY
    }
    private fun next() {
        vibrator().cancel();tts?.stop();handler.removeCallbacksAndMessages(null)
        wakeLock?.let { if(it.isHeld) it.release() };wakeLock=null;current=null
        val next=repo.store.all().firstOrNull { it.status=="ringing" }
        if(next!=null) onStartCommand(Intent(this,javaClass).putExtra("id",next.id),0,0) else { current=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null);vibrator().cancel();tts?.shutdown();wakeLock?.let { if(it.isHeld) it.release() };wakeLock=null;super.onDestroy() }
    companion object { const val ACK="reminder.ack";const val SNOOZE="reminder.snooze" }
}
