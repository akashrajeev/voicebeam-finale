package com.akashrajeev.voicebeam.core

import java.util.Locale

/** One finished caption line. Times are milliseconds from the session start. */
data class CaptionSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val isTarget: Boolean,
)

object Captions {
    /** The ASR model outputs UPPERCASE words; make them readable. */
    fun tidy(raw: String): String {
        val words = raw.trim().lowercase(Locale.US).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        val fixed = words.map { w ->
            when {
                w == "i" -> "I"
                w.startsWith("i'") -> "I" + w.substring(1)
                else -> w
            }
        }
        val s = fixed.joinToString(" ")
        return s.replaceFirstChar { it.titlecase(Locale.US) }
    }

    fun srtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        val milli = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }

    fun toSrt(segments: List<CaptionSegment>, includeOthers: Boolean = true, speakerNames: Boolean = false): String {
        val sb = StringBuilder()
        var n = 1
        for (seg in segments) {
            if (!includeOthers && !seg.isTarget) continue
            if (seg.text.isBlank()) continue
            val end = if (seg.endMs <= seg.startMs) seg.startMs + 1000 else seg.endMs
            sb.append(n++).append('\n')
            sb.append(srtTime(seg.startMs)).append(" --> ").append(srtTime(end)).append('\n')
            if (speakerNames) sb.append(if (seg.isTarget) "Speaker 1: " else "Others: ")
            sb.append(seg.text).append("\n\n")
        }
        return sb.toString()
    }

    fun toText(segments: List<CaptionSegment>): String = segments.filter { it.text.isNotBlank() }.joinToString("\n") {
        val who = if (it.isTarget) "Speaker 1" else "Others"
        "[${clock(it.startMs)}] $who: ${it.text}"
    }

    fun clock(ms: Long): String {
        val totalS = ms / 1000
        return String.format(Locale.US, "%02d:%02d", totalS / 60, totalS % 60)
    }

    /** Caption to show at a given playback time (used when burning captions into video). */
    fun activeAt(segments: List<CaptionSegment>, timeMs: Long, linger: Long = 1500): CaptionSegment? =
        segments.lastOrNull { timeMs >= it.startMs && timeMs <= maxOf(it.endMs, it.startMs + 800) + linger && it.text.isNotBlank() }
}
