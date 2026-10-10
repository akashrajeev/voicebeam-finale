package com.akashrajeev.voicebeam.footage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private atomic metadata. Video/audio stay in each clip directory, no external upload. */
class FootageStore(context: Context) {
    val root=File(context.filesDir,"footage").apply { mkdirs() }
    @Synchronized fun save(clip: FootageClip) {
        val dir=File(root,clip.id).apply { mkdirs() }
        val json=JSONObject().put("id",clip.id).put("title",clip.title).put("video",clip.video).put("audio",clip.audio)
            .put("duration",clip.duration).put("status",clip.status).put("selected",clip.selected?:-999)
        json.put("windows",JSONArray().apply { clip.windows.forEach { put(JSONObject().put("start",it.start).put("end",it.end).put("group",it.group).put("level",it.level).put("similarity",it.similarity)) } })
        json.put("names",JSONObject().apply { clip.names.forEach { (id,name) -> put(id.toString(),name) } })
        json.put("lines",JSONArray().apply { clip.lines.forEach { put(JSONObject().put("start",it.start).put("end",it.end).put("group",it.group).put("text",it.text).put("status",it.status)) } })
        val file=android.util.AtomicFile(File(dir,"clip.json"));val output=file.startWrite()
        try { output.write(json.toString().toByteArray());file.finishWrite(output) } catch(t: Throwable) { file.failWrite(output);throw t }
    }
    @Synchronized fun clips(): List<FootageClip> = root.listFiles().orEmpty().mapNotNull { dir ->
        runCatching {
            val j=JSONObject(android.util.AtomicFile(File(dir,"clip.json")).readFully().toString(Charsets.UTF_8))
            val w=j.getJSONArray("windows");val l=j.getJSONArray("lines");val names=j.getJSONObject("names")
            FootageClip(j.getString("id"),j.getString("title"),j.getString("video"),j.getString("audio"),j.getLong("duration"),j.getString("status"),
                (0 until w.length()).map { i -> val x=w.getJSONObject(i);FootageWindow(x.getLong("start"),x.getLong("end"),x.getInt("group"),x.getDouble("level").toFloat(),x.getDouble("similarity").toFloat()) },
                names.keys().asSequence().associate { it.toInt() to names.getString(it) },j.optInt("selected",-999).takeIf { it!=-999 },
                (0 until l.length()).map { i -> val x=l.getJSONObject(i);FootageLine(x.getLong("start"),x.getLong("end"),x.getInt("group"),x.getString("text"),x.getString("status")) })
        }.getOrNull()
    }.sortedByDescending { it.id }
    @Synchronized fun delete(id: String) { require(id.matches(Regex("[0-9a-f-]+")));File(root,id).deleteRecursively() }
}
