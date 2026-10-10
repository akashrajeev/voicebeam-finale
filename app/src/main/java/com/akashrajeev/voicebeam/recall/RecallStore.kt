package com.akashrajeev.voicebeam.recall

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecallStore(context: Context) : SQLiteOpenHelper(context, "recall.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY, start INTEGER NOT NULL, title TEXT NOT NULL)")
        db.execSQL("CREATE TABLE segments(id INTEGER PRIMARY KEY, session INTEGER NOT NULL, start INTEGER NOT NULL, duration INTEGER NOT NULL, path TEXT NOT NULL, text TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'queued', speaker TEXT NOT NULL DEFAULT '', vector BLOB, notes TEXT NOT NULL DEFAULT '[]')")
        db.execSQL("CREATE INDEX segment_session ON segments(session,start)")
        db.execSQL("CREATE INDEX segment_status ON segments(status,id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    fun session(start: Long): Long = writableDatabase.insertOrThrow("sessions", null, ContentValues().apply {
        put("start", start); put("title", "Conversation")
    })
    fun add(session: Long, start: Long, duration: Long, file: File) = writableDatabase.insertOrThrow("segments", null, ContentValues().apply {
        put("session", session); put("start", start); put("duration", duration); put("path", file.absolutePath)
    })
    fun sessions(): List<RecallSession> = readableDatabase.rawQuery("SELECT id,start,title FROM sessions ORDER BY start DESC", null).use { c ->
        buildList { while(c.moveToNext()) add(RecallSession(c.getLong(0),c.getLong(1),c.getString(2))) }
    }
    fun segments(session: Long? = null): List<RecallSegment> = readableDatabase.rawQuery(
        "SELECT id,session,start,duration,path,text,status,speaker,vector,notes FROM segments" + if(session == null) " ORDER BY id" else " WHERE session=? ORDER BY start",
        session?.let { arrayOf(it.toString()) }
    ).use { c -> buildList { while(c.moveToNext()) {
        val vector = if(c.isNull(8)) null else c.getBlob(8).let { bytes ->
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); FloatArray(bytes.size/4) { b.float }
        }
        add(RecallSegment(c.getLong(0),c.getLong(1),c.getLong(2),c.getLong(3),c.getString(4),c.getString(5),c.getString(6),c.getString(7),vector,c.getString(9)))
    } } }
    fun nextQueued(): RecallSegment? {
        val id=readableDatabase.rawQuery("SELECT id,session FROM segments WHERE status IN ('queued','transcribed') ORDER BY id LIMIT 1",null).use { c ->
            if(c.moveToFirst()) c.getLong(0) to c.getLong(1) else null
        } ?: return null
        return segments(id.second).find { it.id==id.first }
    }
    fun retryFailed() = writableDatabase.execSQL("UPDATE segments SET status=CASE WHEN text='' THEN 'queued' ELSE 'transcribed' END WHERE status IN ('retry','needs_index')")
    fun update(id: Long, text: String? = null, status: String? = null, vector: FloatArray? = null, notes: String? = null, speaker: String? = null) {
        val v = ContentValues().apply {
            text?.let { put("text",it) }; status?.let { put("status",it) }; notes?.let { put("notes",it) }; speaker?.let { put("speaker",it) }
            vector?.let { a -> val b = ByteBuffer.allocate(a.size*4).order(ByteOrder.LITTLE_ENDIAN); a.forEach { b.putFloat(it) }; put("vector",b.array()) }
        }
        writableDatabase.update("segments",v,"id=?",arrayOf(id.toString()))
    }
    fun rename(id: Long, name: String) = writableDatabase.update("sessions",ContentValues().apply { put("title",name.trim().take(100)) },"id=?",arrayOf(id.toString()))
    fun delete(session: Long) {
        val files = segments(session).map { File(it.path) }
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("segments","session=?",arrayOf(session.toString()))
            writableDatabase.delete("sessions","id=?",arrayOf(session.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        files.forEach { it.delete() }
    }
}
