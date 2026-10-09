package com.akashrajeev.voicebeam.engine

import android.os.SystemClock
import com.akashrajeev.voicebeam.BuildConfig
import com.akashrajeev.voicebeam.core.DiagnosticLog

/** Local RAM only. Never stores samples, captions, face data, device names or identifiers. */
object Diagnostics {
    private val log = DiagnosticLog()
    private val backendStatus = com.akashrajeev.voicebeam.core.BackendStatus()
    fun backend(state:String) { backendStatus.set(state);event("hearing_status="+backendStatus.value) }
    fun event(state: String) = log.add("${SystemClock.elapsedRealtime()}ms $state")
    fun snapshot(): String = "VoiceBeam ${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} commit=${BuildConfig.LAB_COMMIT} track=ENH separation=not-included\nhearing_status=" + backendStatus.value + "\n" + log.snapshot()
    fun clear() { log.clear();backendStatus.clear() }
}
