package com.akashrajeev.voicebeam.reminders

import org.json.JSONArray
import org.json.JSONObject
import java.time.*

data class ReminderField(val value: String,val quote: String)
data class ReminderCard(val id: String,val created: Long,val audio: String,val transcript: String,
    val fields: Map<String,ReminderField>,val status: String="review",val due: Long?=null,val repeatMinutes: Long?=null,
    val token: String="",val counter: String="",val alertAudio: String="",val alertText: String="",val occurrence: Long=0) {
    val what get()=fields["what"]?.value.orEmpty()
    fun text()=fields.entries.joinToString("\n") { "${it.key}: ${it.value.value}" }
}
object ReminderGrounding {
    val keys=setOf("what","when","bring","place","contact","repeat","token","counter")
    /** Every populated value is a literal transcript substring. Semantic meaning still needs review. */
    fun extract(raw: String,transcript: String): List<Map<String,ReminderField>> {
        require(transcript.length<=12000)
        val clean=raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val array=JSONArray(clean);require(array.length()<=12)
        return (0 until array.length()).mapNotNull { index ->
            val obj=array.optJSONObject(index)?:return@mapNotNull null
            val fields=keys.mapNotNull { key ->
                val item=obj.optJSONObject(key)?:return@mapNotNull null
                val value=item.optString("value").trim();val quote=item.optString("quote").trim()
                if(value.isNotEmpty() && value.length<=600 && quote.isNotEmpty() && transcript.contains(quote) && quote.contains(value)) key to ReminderField(value,quote) else null
            }.toMap()
            fields.takeIf { it["what"]?.value?.isNotBlank()==true }
        }
    }
    fun nextOccurrence(previous: Long,repeatMinutes: Long,now: Long): Long {
        require(repeatMinutes in 1..525600)
        val delta=Math.multiplyExact(repeatMinutes,60000L)
        return Math.addExact(previous,Math.multiplyExact(maxOf(1,(now-previous)/delta+1),delta))
    }
    fun localTime(date: String,time: String,zone: ZoneId): Long {
        val local=LocalDateTime.of(LocalDate.parse(date),LocalTime.parse(time))
        require(zone.rules.getValidOffsets(local).size==1) { "This time changes with daylight saving. Choose another time." }
        return local.atZone(zone).toInstant().toEpochMilli()
    }
    fun tokenMatches(text: String,token: String,counter: String): Boolean {
        if(!token.matches(Regex("[0-9]{1,6}"))) return false
        val words=text.lowercase()
        if(Regex("\\b(not|never|wait|waiting|cancelled|tomorrow)\\b").containsMatchIn(words)) return false
        if(!Regex("\\b(called|calling|proceed|go|come|counter)\\b").containsMatchIn(words)) return false
        if(!Regex("\\b(token|number|ticket)\\b").containsMatchIn(words)) return false
        val number=Regex("(?<![0-9])${Regex.escape(token)}(?![0-9])")
        if(!number.containsMatchIn(words)) return false
        if(counter.isNotBlank() && !Regex("\\bcounter\\s+${Regex.escape(counter)}\\b",RegexOption.IGNORE_CASE).containsMatchIn(text)) return false
        return true
    }
}
object ReminderCodec {
    fun encode(c: ReminderCard): JSONObject=JSONObject().put("id",c.id).put("created",c.created).put("audio",c.audio).put("transcript",c.transcript)
        .put("status",c.status).put("due",c.due?:0).put("repeat",c.repeatMinutes?:0).put("token",c.token).put("counter",c.counter)
        .put("alertAudio",c.alertAudio).put("alertText",c.alertText).put("occurrence",c.occurrence)
        .put("fields",JSONObject().apply { c.fields.forEach { (k,v)->put(k,JSONObject().put("value",v.value).put("quote",v.quote)) } })
    fun decode(j: JSONObject): ReminderCard {
        val f=j.getJSONObject("fields")
        return ReminderCard(j.getString("id"),j.getLong("created"),j.getString("audio"),j.getString("transcript"),
            f.keys().asSequence().associateWith { val v=f.getJSONObject(it);ReminderField(v.getString("value"),v.getString("quote")) },
            j.getString("status"),j.optLong("due").takeIf { it>0 },j.optLong("repeat").takeIf { it>0 },j.optString("token"),j.optString("counter"),j.optString("alertAudio"),j.optString("alertText"),j.optLong("occurrence"))
    }
}
