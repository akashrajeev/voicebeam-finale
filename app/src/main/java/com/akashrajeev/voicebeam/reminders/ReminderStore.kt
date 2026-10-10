package com.akashrajeev.voicebeam.reminders
import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject
class ReminderStore(context: Context) {
    val root=File(context.filesDir,"reminders").apply { mkdirs() }
    @Synchronized fun save(c: ReminderCard) {
        require(c.id.matches(Regex("[a-z0-9-]+")))
        val f=AtomicFile(File(root,"${c.id}.json"));val out=f.startWrite()
        try { out.write(ReminderCodec.encode(c).toString().toByteArray());f.finishWrite(out) } catch(t: Throwable) { f.failWrite(out);throw t }
    }
    @Synchronized fun all(): List<ReminderCard> = root.listFiles().orEmpty().filter { it.extension=="json" }.mapNotNull {
        runCatching { ReminderCodec.decode(JSONObject(AtomicFile(it).readFully().toString(Charsets.UTF_8))) }.getOrNull()
    }.sortedByDescending { it.created }
    fun get(id: String)=all().find { it.id==id }
}
