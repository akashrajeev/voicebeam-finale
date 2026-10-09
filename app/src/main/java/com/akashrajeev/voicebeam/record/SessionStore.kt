package com.akashrajeev.voicebeam.record

import android.content.Context
import com.akashrajeev.voicebeam.core.CaptionSegment
import com.akashrajeev.voicebeam.core.Captions
import com.akashrajeev.voicebeam.core.SessionIds
import com.akashrajeev.voicebeam.engine.SaveMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val mode: SaveMode,
    val captions: String,          // "burned" | "srt" | "none"
    val dir: File,
) {
    val video: File get() = File(dir, "video.mp4")
    val cleanAudio: File get() = File(dir, "clean.m4a")
    val cleanWav: File get() = File(dir, "clean.wav")
    val rawWav: File get() = File(dir, "raw.wav")
    val srt: File get() = File(dir, "captions.srt")
    val txt: File get() = File(dir, "transcript.txt")
    val thumb: File get() = File(dir, "thumb.jpg")
}

class SessionStore(private val context: Context) {
    private val root: File get() = File(context.filesDir, "sessions").apply { mkdirs() }

    fun newSessionDir(): Pair<String, File> {
        // mkdir() is atomic: it fails if the folder exists, so two starts in the same second cannot share one.
        while (true) {
            val id = SessionIds.next(Date()) { File(root, it).exists() }
            val dir = File(root, id)
            if (dir.mkdir()) return Pair(id, dir)
        }
    }

    fun writeMeta(m: SessionMeta, segments: List<CaptionSegment>) {
        val json = JSONObject()
            .put("id", m.id).put("title", m.title).put("createdAt", m.createdAt)
            .put("durationMs", m.durationMs).put("mode", m.mode.name).put("captions", m.captions)
            .put("segments", JSONArray().apply {
                segments.forEach { put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text).put("target", it.isTarget)) }
            })
        File(m.dir, "session.json").writeText(json.toString())
        m.srt.writeText(Captions.toSrt(segments))
        m.txt.writeText(Captions.toText(segments))
    }

    fun list(): List<SessionMeta> = root.listFiles()?.mapNotNull { load(it) }?.sortedByDescending { it.createdAt } ?: emptyList()

    fun load(dir: File): SessionMeta? = try {
        val j = JSONObject(File(dir, "session.json").readText())
        SessionMeta(j.getString("id"), j.getString("title"), j.getLong("createdAt"), j.getLong("durationMs"),
            SaveMode.valueOf(j.getString("mode")), j.optString("captions", "srt"), dir)
    } catch (_: Throwable) { null }

    fun segments(m: SessionMeta): List<CaptionSegment> = try {
        val arr = JSONObject(File(m.dir, "session.json").readText()).getJSONArray("segments")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            CaptionSegment(o.getLong("s"), o.getLong("e"), o.getString("t"), o.getBoolean("target"))
        }
    } catch (_: Throwable) { emptyList() }

    fun rename(m: SessionMeta, title: String) {
        val f = File(m.dir, "session.json")
        val j = JSONObject(f.readText()).put("title", title)
        f.writeText(j.toString())
    }

    fun delete(m: SessionMeta) { m.dir.deleteRecursively() }

    fun freeBytes(): Long = context.filesDir.usableSpace
}
