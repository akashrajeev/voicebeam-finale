package com.akashrajeev.voicebeam.engine

import android.os.SystemClock
import com.akashrajeev.voicebeam.BuildConfig
import com.akashrajeev.voicebeam.core.DiagnosticLog

/** Local RAM only. Never stores samples, captions, face data, device names or identifiers. */
object Diagnostics {
    private val log = DiagnosticLog()
    fun event(state: String) = log.add("${SystemClock.elapsedRealtime()}ms $state")
    fun snapshot(): String = "VoiceBeam ${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} commit=${BuildConfig.LAB_COMMIT} track=ENH separation=not-included\n" + log.snapshot()
    fun clear() = log.clear()
}
