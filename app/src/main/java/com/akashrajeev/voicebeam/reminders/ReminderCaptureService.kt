package com.akashrajeev.voicebeam.reminders
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.akashrajeev.voicebeam.VoiceBeamApp
import com.akashrajeev.voicebeam.MainActivity
import com.akashrajeev.voicebeam.core.WavWriter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
class ReminderCaptureService : Service() {
    private val running=AtomicBoolean(false)
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var recorder: AudioRecord?=null
    private val repo get()=(application as VoiceBeamApp).reminders
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,id: Int): Int {
        if(intent?.action==STOP) { running.set(false);runCatching { recorder?.stop() };return START_NOT_STICKY }
        if(intent?.action!=START || !running.compareAndSet(false,true)) return START_NOT_STICKY
        val watch=intent.getStringExtra("watch")
        try {
            check(!(application as VoiceBeamApp).recall.state.value.recording) { "Pause Recall before listening for instructions" }
            check(!repo.state.value.busy) { "Wait for the current recording to finish" }
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("reminder-capture","Instruction listening",NotificationManager.IMPORTANCE_LOW))
            val stop=PendingIntent.getService(this,81,Intent(this,javaClass).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
            val open=PendingIntent.getActivity(this,82,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
            val note=NotificationCompat.Builder(this,"reminder-capture").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(if(watch==null) "Listening for instructions" else "My Turn is listening").setContentText("Tap Stop to close the microphone. Audio stays on this phone.")
                .setContentIntent(open).setOngoing(true).addAction(0,"Stop",stop).build()
            if(android.os.Build.VERSION.SDK_INT>=29) startForeground(81,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(81,note)
            (application as VoiceBeamApp).engine.stopListening()
            if(watch!=null) {
                val c=requireNotNull(repo.store.get(watch));require(c.status in setOf("turn_ready","watching"));repo.store.save(c.copy(status="watching"))
            }
            repo.recording(true,watch);repo.refresh("Listening. Tap Stop when the instruction is finished.")
            worker.execute { capture(watch) }
        } catch(t: Exception) { running.set(false);repo.recording(false);repo.refresh(t.message?:"Microphone unavailable");stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
        return START_NOT_STICKY
    }
    private fun capture(watch: String?) {
        var file: File?=null;var writer: WavWriter?=null
        try {
            val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            require(minimum>0)
            @Suppress("MissingPermission") val input=AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum,32000))
            recorder=input;check(input.state==AudioRecord.STATE_INITIALIZED);input.startRecording()
            val pcm=ShortArray(1600);val floats=FloatArray(1600);var total=0L
            while(running.get()) {
                if(watch!=null && repo.store.get(watch)?.status!="watching") break
                val n=input.read(pcm,0,pcm.size);if(n<0) { if(running.get()) error("Microphone read failed");break };if(n==0) continue
                if(writer==null) { file=File(repo.store.root,"audio-${java.util.UUID.randomUUID()}.wav");writer=WavWriter(file!!,16000) }
                for(i in 0 until n) floats[i]=pcm[i]/32768f
                check(filesDir.usableSpace>20_000_000) { "Free storage before continuing" }
                writer!!.write(floats,n);total+=n
                if(watch!=null && writer!!.durationMs>=8000) {
                    if(repo.state.value.busy) { writer!!.close();file!!.delete();writer=null;file=null;repo.refresh("Processing is slower than announcements; some audio is skipped. Keep checking the display.");continue }
                    writer!!.close();repo.process(file!!,watch);writer=null;file=null
                }
                if(watch==null && total>=16000L*25) { repo.refresh("25-second recording saved. Record another instruction when ready.");break }
                if(watch!=null && total>=16000L*1800) { repo.refresh("Turn listening paused after 30 minutes. Tap Start to continue.");break }
            }
        } catch(t: Exception) { repo.refresh(t.message?:"Recording paused") }
        finally {
            runCatching { recorder?.stop() };recorder?.release();recorder=null
            runCatching { writer?.close() }
            file?.let { if(it.length()>32044) { if(watch==null || !repo.state.value.busy) repo.process(it,watch) else it.delete() } else it.delete() }
            if(watch!=null) repo.store.get(watch)?.takeIf { it.status=="watching" }?.let { repo.store.save(it.copy(status="turn_ready")) }
            running.set(false);repo.recording(false);repo.refresh();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
        }
    }
    override fun onDestroy() { running.set(false);runCatching { recorder?.stop() };worker.shutdown();super.onDestroy() }
    companion object { const val START="reminder.capture.start";const val STOP="reminder.capture.stop" }
}
