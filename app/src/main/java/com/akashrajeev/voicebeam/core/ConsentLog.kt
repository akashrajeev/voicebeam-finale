package com.akashrajeev.voicebeam.core

import java.io.File

/**
 * Local, minimal consent record. One line per decision: time, event, face id,
 * lip score, and at most 40 chars of what was heard. No audio, no images, never
 * uploaded. Deletable.
 */
class ConsentLog(private val file: File) {
    @Synchronized fun add(tsMs: Long, event: String, faceId: Int?, lip: Float?, heard: String?) {
        val h = (heard ?: "").take(40).replace("\\", " ").replace("\"", "'").replace("\n", " ")
        file.parentFile?.mkdirs()
        file.appendText("{\"t\":$tsMs,\"e\":\"$event\",\"face\":${faceId ?: -1},\"lip\":${lip?.let { "%.2f".format(java.util.Locale.US, it) } ?: "null"},\"heard\":\"$h\"}\n")
    }
    @Synchronized fun count(): Int = if (file.exists()) file.readLines().count { it.isNotBlank() } else 0
    @Synchronized fun deleteAll(): Boolean = !file.exists() || file.delete()
}
