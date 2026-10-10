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
                    fields.forEach { f -> store.save(ReminderCard(UUID.randomUUID().toString(),System.currentTimeMillis(),file.path,text,f)) }
                    refresh(if(extracted.isEmpty()) "No clear instruction found. Replay or add the details yourself." else "Check what I understood. Nothing is set yet.")
                }
            } catch(t: Exception) {
                if(watch==null) store.save(ReminderCard(UUID.randomUUID().toString(),System.currentTimeMillis(),file.path,"",emptyMap())) else file.delete()
                refresh(t.message?:"Please replay and try again")
            } finally { synchronized(this@ReminderRepository) { _state.value=_state.value.copy(busy=false,cards=store.all()) } }
        }
    }
    fun manual(): String {
        val id=UUID.randomUUID().toString();store.save(ReminderCard(id,System.currentTimeMillis(),"","",emptyMap()));refresh("Enter the instruction, then check and confirm it");return id
    }
    fun confirm(id: String,fields: Map<String,String>,due: Long?,repeat: Long?,token: String,counter: String): String? {
        val c=store.get(id)?:return "Card is unavailable"
        if(fields["what"].isNullOrBlank()) return "Please enter what to remember"
        if(due==null && token.isBlank()) return "Choose an exact date and time, or a turn number"
        if(due!=null && due<=System.currentTimeMillis()) return "Choose a future date and time"
        if(repeat!=null && (repeat !in 1..525600 || due==null)) return "Enter a repeat interval in minutes after setting a time"
        if(token.isNotBlank() && !token.matches(Regex("[0-9]{1,6}"))) return "Enter a numeric turn number"
        if(due!=null && token.isNotBlank()) return "Choose a timed reminder or My Turn, not both"
        // Single confirmation changes status once; guard accidental repeated Yes taps.
        if(c.status in setOf("ringing","acknowledged","cancelled")) return "This card is no longer waiting for confirmation"
        val next=c.copy(fields=fields.filterValues { it.isNotBlank() }.mapValues { (k,v)->ReminderField(v,c.fields[k]?.quote.orEmpty()) },
            due=due,repeatMinutes=repeat,token=token,counter=counter,status=if(token.isNotBlank()) "turn_ready" else "confirmed")
        if(!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return "Allow notifications before confirming an alert"
        if(due!=null) ReminderScheduler(context).readiness()?.let { return it }
        store.save(next)
        if(due!=null) try { ReminderScheduler(context).schedule(next) } catch(t: Exception) { store.save(next.copy(status="needs_permission"));refresh();return t.message?:"Alert could not be set" }
        refresh("Reminder confirmed");return null
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
