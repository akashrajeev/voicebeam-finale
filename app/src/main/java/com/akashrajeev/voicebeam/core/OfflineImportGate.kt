package com.akashrajeev.voicebeam.core

/**
 * Pure idle-eligibility gate for the offline footage import (RAM/mic protection on the unified Recall + isolation build).
 * Import may start only when NOTHING else owns the mic or is processing: Recall recording / busy (queued processing) / asking / recapping,
 * and the Listen engine listening / recording / exporting. Any single flag blocks. [Blocker] is reported in a fixed priority so the UI can say why.
 */
object OfflineImportGate {
    data class Activity(
        val recallRecording: Boolean = false, val recallBusy: Boolean = false, val recallAsking: Boolean = false, val recallRecapping: Boolean = false,
        val listening: Boolean = false, val recordingActive: Boolean = false, val recordingExporting: Boolean = false
    )

    enum class Blocker(val message: String) {
        RECALL_RECORDING("Pause Recall recording first"),
        LISTENING("Stop Listen first"),
        RECORDING("Finish the current recording first"),
        EXPORTING("Wait for the export to finish"),
        RECALL_BUSY("Wait for Recall to finish processing"),
        RECALL_ASKING("Wait for Recall to finish answering"),
        RECALL_RECAPPING("Wait for Recall to finish the recap")
    }

    /** First blocker in priority order, or null when the import may run. */
    fun blocker(a: Activity): Blocker? = when {
        a.recallRecording -> Blocker.RECALL_RECORDING
        a.listening -> Blocker.LISTENING
        a.recordingActive -> Blocker.RECORDING
        a.recordingExporting -> Blocker.EXPORTING
        a.recallBusy -> Blocker.RECALL_BUSY
        a.recallAsking -> Blocker.RECALL_ASKING
        a.recallRecapping -> Blocker.RECALL_RECAPPING
        else -> null
    }

    fun allowed(a: Activity): Boolean = blocker(a) == null

    fun allowed(recallRecording: Boolean, recallBusy: Boolean, recallAsking: Boolean, recallRecapping: Boolean,
                listening: Boolean, recordingActive: Boolean, recordingExporting: Boolean): Boolean =
        allowed(Activity(recallRecording, recallBusy, recallAsking, recallRecapping, listening, recordingActive, recordingExporting))
}
