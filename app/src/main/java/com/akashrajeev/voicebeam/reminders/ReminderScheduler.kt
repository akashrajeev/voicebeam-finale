package com.akashrajeev.voicebeam.reminders
import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import android.provider.Settings
class ReminderScheduler(private val context: Context) {
    private val alarms=context.getSystemService(AlarmManager::class.java)
    fun readiness(): String? {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.getNotificationChannel("reminder-alert")?.let { if(it.importance==NotificationManager.IMPORTANCE_NONE) return "Enable the Confirmed reminders notification channel" }
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled()) return "Allow notifications before setting alerts"
        if(Build.VERSION.SDK_INT>=31 && !alarms.canScheduleExactAlarms()) return "Allow exact alarms before setting a time"
        return null
    }
    private fun intent(card: ReminderCard)=PendingIntent.getBroadcast(context,0,Intent(context,ReminderReceiver::class.java).setAction("reminder.due").setData(android.net.Uri.parse("voicebeam://reminder/${card.id}")).putExtra("id",card.id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(card: ReminderCard) {
        check(readiness()==null) { readiness().orEmpty() }
        val at=requireNotNull(card.due)
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,intent(card))
    }
    fun cancel(card: ReminderCard) { alarms.cancel(intent(card)) }
    fun restore() {
        val store=ReminderStore(context)
        for(c in store.all().filter { it.status in setOf("confirmed","snoozed","needs_permission","ringing") && it.due!=null }) {
            val next=if(readiness()!=null) c.copy(status="needs_permission") else c.copy(status=if(c.status=="snoozed") "snoozed" else "confirmed",due=maxOf(c.due!!,System.currentTimeMillis()+1500))
            store.save(next);if(next.status!="needs_permission") runCatching { schedule(next) }.onFailure { store.save(next.copy(status="needs_permission")) }
        }
    }
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action!="reminder.due") { ReminderScheduler(context).restore();return }
        val id=intent.getStringExtra("id")?:return;val store=ReminderStore(context);val c=store.get(id)?:return
        if(c.status !in setOf("confirmed","snoozed")) return
        if(c.due==null || c.due>System.currentTimeMillis()+2000) return
        store.save(c.copy(status="ringing",occurrence=System.currentTimeMillis()))
        (context.applicationContext as com.akashrajeev.voicebeam.VoiceBeamApp).reminders.refresh()
        runCatching { context.startForegroundService(Intent(context,ReminderAlertService::class.java).putExtra("id",id)) }.onFailure { store.save(c.copy(status="missed")) }
    }
}
