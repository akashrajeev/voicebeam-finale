package com.akashrajeev.voicebeam.reminders
import java.time.*
/** Deterministic resolution only for unambiguous supported spoken time forms. No model date guessing. */
object ReminderTime {
    data class Resolution(val due: Long?,val reason: String)
    fun resolve(phrase: String,now: Long,zone: ZoneId): Resolution {
        var p=phrase.lowercase().trim().replace(Regex("(?<=\\d)\\s*a\\.?m\\.?\\b")," am").replace(Regex("(?<=\\d)\\s*p\\.?m\\.?\\b")," pm")
        val small=mapOf("one" to 1,"two" to 2,"three" to 3,"four" to 4,"five" to 5,"six" to 6,"seven" to 7,"eight" to 8,"nine" to 9,"ten" to 10,"eleven" to 11,"twelve" to 12,"fifteen" to 15,"twenty" to 20,"thirty" to 30)
        for((word,n) in small) p=p.replace(Regex("\\b$word\\b"),n.toString())
        fun no(reason: String)=Resolution(null,reason)
        if(p.isBlank()) return no("What day and time should I alert you?")
        if(Regex("\\b(if|unless|not|don't|never|every|twice|daily|after food|before food)\\b").containsMatchIn(p)) return no("Please choose the alert time and any repeat yourself")
        if(Regex("\\b(or|between|around|about|maybe|possibly)\\b").containsMatchIn(p)) return no("Please choose one exact time")
        val relative=Regex("^in (\\d{1,4}) (minute|minutes|hour|hours)[.!]?$ ".trim()).findAll(p).toList()
        if(relative.size==1 && !Regex("\\b(today|tomorrow|am|pm)\\b").containsMatchIn(p)) {
            val n=relative.single().groupValues[1].toLong();val step=if(relative.single().groupValues[2].startsWith("hour")) 3600000L else 60000L
            if(n in 1..1440 && n*step<=7*86400000L) return Resolution(now+n*step,"Clear interval")
        }
        val times=Regex("(?<![0-9])(\\d{1,2})(?::([0-9]{2}))?\\s*(am|pm)\\b").findAll(p).toList()
        if(times.size!=1) return no("Please specify AM or PM and one alert time")
        val t=times.single();val h=t.groupValues[1].toInt();val m=t.groupValues[2].ifBlank { "0" }.toInt()
        if(h !in 1..12 || m !in 0..59) return no("Please check that time")
        val hour=h%12+if(t.groupValues[3]=="pm") 12 else 0
        val base=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dates=Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b").findAll(p).toList()
        val days=Regex("\\b(today|tomorrow)\\b").findAll(p).toList()
        val date=when {
            dates.size==1 && days.isEmpty() -> runCatching { LocalDate.parse(dates.single().value) }.getOrNull()?:return no("Please check the date")
            dates.isEmpty() && days.size==1 -> if(days.single().value=="tomorrow") base.plusDays(1) else base
            else -> return no("Which date should I use?")
        }
        val remainder=p.replace(t.value,"").replace(dates.firstOrNull()?.value?:days.firstOrNull()?.value.orEmpty(),"").replace(Regex("\\b(at|on)\\b"),"").trim().trim('.',',','!').trim()
        if(remainder.isNotBlank()) return no("Please choose an exact date and time")
        val local=date.atTime(hour,m)
        if(zone.rules.getValidOffsets(local).size!=1) return no("Please choose a time outside the clock change")
        val due=local.atZone(zone).toInstant().toEpochMilli()
        return if(due<=now) no("That time has passed. Please choose a future time") else Resolution(due,"Explicit day and AM/PM")
    }
}
