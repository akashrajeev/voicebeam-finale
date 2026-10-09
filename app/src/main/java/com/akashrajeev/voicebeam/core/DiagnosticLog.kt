package com.akashrajeev.voicebeam.core

import java.util.ArrayDeque

/** In-memory bounded diagnostic events. Callers supply technical state only, never speech/text. */
class DiagnosticLog(private val capacity: Int = 300) {
    init { require(capacity > 0) }
    private val lines = ArrayDeque<String>()
    @Synchronized fun add(line: String) {
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast(line.take(2048))
    }
    @Synchronized fun snapshot(): String = lines.joinToString("\n")
    @Synchronized fun clear() = lines.clear()
}
