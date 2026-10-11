package com.akashrajeev.voicebeam.engine

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The locked person's voice template from Listen's "Learn locked voice", kept ON THIS PHONE so offline video isolation can use it as the reference.
 * Deleted when consent is withdrawn or consent records are deleted. Never leaves the device.
 */
object EnrolledVoiceStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "enrolled_voice.bin")

    fun save(ctx: Context, centroid: FloatArray) {
        if (centroid.isEmpty() || !centroid.all { it.isFinite() }) return
        val bb = ByteBuffer.allocate(4 + centroid.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(centroid.size); centroid.forEach { bb.putFloat(it) }
        val tmp = File(ctx.filesDir, "enrolled_voice.tmp")
        tmp.writeBytes(bb.array())
        if (!tmp.renameTo(file(ctx))) { file(ctx).writeBytes(bb.array()); tmp.delete() }
    }

    fun load(ctx: Context): FloatArray? = try {
        val f = file(ctx)
        if (!f.exists()) null else {
            val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val n = bb.int
            if (n <= 0 || n > 4096 || bb.remaining() != n * 4) null
            else FloatArray(n) { bb.float }.takeIf { v -> v.all { it.isFinite() } }
        }
    } catch (_: Throwable) { null }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Throwable) {} }
}
