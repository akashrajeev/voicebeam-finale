package com.akashrajeev.voicebeam.core

/**
 * Container start-time policy for decoded phone footage (pure JVM). Re-encoded shares (WhatsApp) often have B-frame / edit-list composition offsets, so MediaExtractor can report a
 * NEGATIVE first sample time for a perfectly good track. Negative is not "missing": a track is missing/empty only when the extractor has no current sample (sampleTrackIndex < 0).
 * Presentation starts at 0, so a negative start is clamped to 0 when no edit-list information is usable.
 */
object TrackStartPolicy {
    fun clamp(startUs: Long): Long = if (startUs < 0L) 0L else startUs

    /** audio start minus video start on the presentation timeline, both clamped to >= 0. Used when the mp4 edit list could not be read. */
    fun offsetUs(audioStartUs: Long, videoStartUs: Long): Long = clamp(audioStartUs) - clamp(videoStartUs)

    /** One line of context for failure messages, e.g. "tracks: video/avc, audio/mp4a-latm". */
    fun summary(mimes: List<String>): String = if (mimes.isEmpty()) "no tracks" else "tracks: " + mimes.joinToString(", ") { it.ifBlank { "?" } }
}
