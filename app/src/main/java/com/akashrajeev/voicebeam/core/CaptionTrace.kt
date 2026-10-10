package com.akashrajeev.voicebeam.core

/**
 * Diagnostic lines that say which caption model is active and which path produced each
 * utterance. Technical values only: never the caption text, never an API key.
 */
object CaptionTrace {
    fun model(name: String): String = "captionModel=" + clean(name)
    /** path is "device" or "groq"; fallback is a short reason code or null. */
    fun utterance(model: String, path: String, ms: Long, audioMs: Long, chars: Int, fallback: String?): String =
        "captionUtterance model=" + clean(model) + " path=" + clean(path) + " ms=" + ms +
            " audioMs=" + audioMs + " chars=" + chars + (if (fallback != null) " fallback=" + clean(fallback) else "")
    private fun clean(v: String): String = v.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }.take(48)
}
