package com.akashrajeev.voicebeam.reminders
import android.content.Context
import com.akashrajeev.voicebeam.VoiceBeamApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

data class ReminderState(val cards: List<ReminderCard> = emptyList(),val busy: Boolean=false,val recording: Boolean=false,val watching: String?=null,val message: String="Ready to listen")
class ReminderRepository(private val context: Context) {
    val store=ReminderStore(context)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val _state=MutableStateFlow(ReminderState(cards=store.all()))
    val state=_state.asStateFlow()
    @Synchronized fun refresh(message: String?=null) { _state.value=_state.value.copy(cards=store.all(),message=message?:_state.value.message) }
    @Synchronized fun recording(value: Boolean,watch: String?=null) { _state.value=_state.value.copy(recording=value,watching=if(value) watch else null) }
    @Synchronized private fun begin(): Boolean { if(state.value.busy) return false;_state.value=_state.value.copy(busy=true);return true }
    fun process(file: File,watch: String?=null) {
        if(!begin()) { refresh("Wait for the previous recording to finish");return }
        scope.launch {
            try {
                refresh(if(watch==null) "Understanding the recording on this phone" else "Checking the announcement")
                val recall=(context.applicationContext as VoiceBeamApp).recall
                val (text,raw)=recall.instructionAudio(file,extract=watch==null)
                if(watch!=null) {
                    val card=store.get(watch)?:error("Turn card is no longer saved")
                    if(card.status=="watching" && ReminderGrounding.tokenMatches(text,card.token,card.counter)) {
                        store.save(card.copy(status="ringing",alertAudio=file.path,alertText=text,occurrence=System.currentTimeMillis()))
                        context.startForegroundService(android.content.Intent(context,ReminderAlertService::class.java).putExtra("id",watch))
                        refresh("Your number was heard. Check the announcement.")
                    } else { file.delete();refresh("Waiting for your number") }
                } else {
                    val extracted=ReminderGrounding.extract(raw,text)
                    // Even no-extraction/error cases keep the original for replay/manual correction.
                    val fields=if(extracted.isEmpty()) listOf(emptyMap()) else extracted
                    val messages=mutableListOf<String>()
                    fields.forEach { f ->
                        val card=ReminderCard(UUID.randomUUID().toString(),System.currentTimeMillis(),file.path,text,f)
                        val repeated=f["repeat"]?.value?.isNotBlank()==true
                        val condition=Regex("\\b(if|unless)\\b",RegexOption.IGNORE_CASE).containsMatchIn(text)
                        val resolved=if(repeated || condition) ReminderTime.Resolution(null,"Please set the repeat or condition yourself") else ReminderTime.resolve(f["when"]?.value.orEmpty(),card.created,java.time.ZoneId.systemDefault())
                        store.save(card)
                        if(resolved.due!=null && card.what.isNotBlank()) {
                            val problem=confirm(card.id,f.mapValues { it.value.value },resolved.due,null,"","")
                            if(problem==null) messages+="Alert set automatically. Check the time or change/cancel it."
                            else messages+="Alert NOT set: $problem"
                        } else messages+=resolved.reason
                    }
                    refresh(if(extracted.isEmpty()) "No clear instruction found. Replay or set an alert yourself." else messages.distinct().joinToString(" · "))
                }
            } catch(t: Exception) {
                if(watch==null) store.save(ReminderCard(UUID.randomUUID().toString(),System.currentTimeMillis(),file.path,"",emptyMap())) else file.delete()
                refresh(t.message?:"Please replay and try again")
            } finally { synchronized(this@ReminderRepository) { _state.value=_state.value.copy(busy=false,cards=store.all()) } }
        }
    }
    fun manual(): String {
        val id=UUID.randomUUID().toString();store.save(ReminderCard(id,System.currentTimeMillis(),"","",emptyMap()));refresh("Enter what to remember, then pick a date and time");return id
    }
    @Synchronized fun confirm(id: String,fields: Map<String,String>,due: Long?,repeat: Long?,token: String,counter: String): String? {
        val c=store.get(id)?:return "Card is unavailable"
        if(fields["what"].isNullOrBlank()) return "Please enter what to remember"
        if(due==null && token.isBlank()) return "Choose an exact date and time, or a turn number"
        if(due!=null && due<=System.currentTimeMillis()) return "That time has passed. Phone time is " + java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm z")) + ". Choose a later time (24-hour clock)."
        if(repeat!=null && (repeat !in 1..525600 || due==null)) return "Enter a repeat interval in minutes after setting a time"
        if(token.isNotBlank() && !token.matches(Regex("[0-9]{1,6}"))) return "Enter a numeric turn number"
        if(due!=null && token.isNotBlank()) return "Choose a timed reminder or My Turn, not both"
        // Single confirmation changes status once; guard accidental repeated Yes taps.
        if(c.status in setOf("ringing","acknowledged","cancelled")) return "This card is no longer waiting for confirmation"
        val next=c.copy(fields=fields.filterValues { it.isNotBlank() }.mapValues { (k,v)->ReminderField(v,c.fields[k]?.quote.orEmpty()) },
            due=due,repeatMinutes=repeat,token=token,counter=counter,status=if(token.isNotBlank()) "turn_ready" else "confirmed")
        val blocked=if(!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) "Allow notifications before setting alerts" else if(due!=null) ReminderScheduler(context).readiness() else null
        if(blocked!=null) { store.save(next.copy(status="needs_permission"));refresh("Alert NOT set: $blocked");return blocked }
        store.save(next)
        if(due!=null) try { ReminderScheduler(context).schedule(next) } catch(t: Exception) { store.save(next.copy(status="needs_permission"));refresh();return t.message?:"Alert could not be set" }
        refresh("Alert set for " + java.time.Instant.ofEpochMilli(due?:System.currentTimeMillis()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")));return null
    }
    fun acknowledge(id: String,snoozeMinutes: Long?=null) {
        val card=store.get(id)?:return
        ReminderScheduler(context).cancel(card)
        val now=System.currentTimeMillis()
        val next=when {
            snoozeMinutes!=null -> card.copy(status="snoozed",due=now+snoozeMinutes*60000)
            card.repeatMinutes!=null && card.due!=null -> card.copy(status="confirmed",due=ReminderGrounding.nextOccurrence(card.due,card.repeatMinutes,now))
            else -> card.copy(status="acknowledged")
        }
        store.save(next)
        if(next.status in setOf("snoozed","confirmed")) runCatching { ReminderScheduler(context).schedule(next) }.onFailure { store.save(next.copy(status="needs_permission")) }
        refresh(if(snoozeMinutes==null) "Alert acknowledged. This does not record a medicine as taken." else "Snoozed for $snoozeMinutes minutes")
    }
    fun cancel(id: String) { val c=store.get(id)?:return;ReminderScheduler(context).cancel(c);store.save(c.copy(status="cancelled"));refresh("Reminder cancelled") }
}
