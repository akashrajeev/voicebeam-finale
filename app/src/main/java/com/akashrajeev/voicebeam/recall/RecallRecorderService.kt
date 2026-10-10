package com.akashrajeev.voicebeam.recall

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.akashrajeev.voicebeam.MainActivity
import com.akashrajeev.voicebeam.VoiceBeamApp
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit user start only, durable disk queue, never auto-restarts after a kill. */
class RecallRecorderService : Service() {
    private val running=AtomicBoolean(false)
    private val executor=Executors.newSingleThreadExecutor()
    @Volatile private var recorder: AudioRecord?=null
    private val repository get()=(application as VoiceBeamApp).recall
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        if(intent?.action==STOP) { stopCapture();return START_NOT_STICKY }
        if(intent?.action!=START || !running.compareAndSet(false,true)) return START_NOT_STICKY
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("recall", "Recall recording",NotificationManager.IMPORTANCE_LOW))
            val pause=PendingIntent.getService(this,2,Intent(this,javaClass).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
            val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
            val note=NotificationCompat.Builder(this,"recall").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Recall is recording").setContentText("Saved locally. Tap Pause to stop the microphone.")
                .setOngoing(true).setContentIntent(open).addAction(0,"Pause",pause).build()
            if(Build.VERSION.SDK_INT>=29) startForeground(42,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(42,note)
            // Listen and Recall never compete for the microphone.
            (application as VoiceBeamApp).engine.stopListening()
            repository.recording(true)
            executor.execute { capture() }
        } catch(t: Exception) { repository.refresh(t.message?:"Microphone permission needed");stopCapture() }
        return START_NOT_STICKY
    }
    private fun capture() {
        val chunker=RecallChunker();var session=0L
        try {
            val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            check(min>0) { "Microphone format unavailable" }
            @Suppress("MissingPermission")
            val input=AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,32000))
            recorder=input;check(input.state==AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            session=repository.store.session(System.currentTimeMillis())
            input.startRecording();check(input.recordingState==AudioRecord.RECORDSTATE_RECORDING)
            val pcm=ShortArray(1600);val floats=FloatArray(1600)
            while(running.get()) {
                val n=input.read(pcm,0,pcm.size)
                if(n<0) { if(running.get()) error("Microphone read failed ($n)") else break }
                if(n==0) continue
                for(i in 0 until n) floats[i]=pcm[i]/32768f
                check(filesDir.usableSpace>20_000_000L) { "Recording paused. Free storage to continue." }
                chunker.add(floats,n) { start,audio -> repository.queued(session,start,audio) }
            }
        } catch(t: Exception) { repository.refresh(t.message?:"Recording paused") }
        finally {
            if(session!=0L) runCatching { chunker.finish { start,audio -> repository.queued(session,start,audio) } }
            runCatching { recorder?.stop() };recorder?.release();recorder=null
            running.set(false);repository.recording(false);repository.refresh()
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
        }
    }
    private fun stopCapture() {
        running.set(false);runCatching { recorder?.stop() }
        repository.recording(false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy() { running.set(false);runCatching { recorder?.stop() };executor.shutdown();super.onDestroy() }
    companion object { const val START="recall.start";const val STOP="recall.stop" }
}
