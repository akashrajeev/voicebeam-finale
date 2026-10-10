package com.akashrajeev.voicebeam.cloud

object ElevenKey {
    /** Trimmed key, or null when it does not look like an API key. Never logged. */
    fun parse(raw: String?): String? {
        val k = raw?.trim() ?: return null
        if (k.length < 16 || k.length > 200) return null
        if (!k.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
        return k
    }
}
