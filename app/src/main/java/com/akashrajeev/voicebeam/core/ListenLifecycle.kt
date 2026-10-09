package com.akashrajeev.voicebeam.core

/** Where the listening pipeline is. Start and stop may be asked for from any thread. */
enum class ListenPhase { IDLE, STARTING, LISTENING, STOPPING }

/**
 * Small explicit state machine for the listening pipeline. It makes start and
 * stop idempotent and refuses a start while a stop is still in progress.
 */
class ListenLifecycle {
    @Volatile var phase: ListenPhase = ListenPhase.IDLE
        private set

    /** IDLE -> STARTING. False if already starting, listening or stopping. */
    @Synchronized fun beginStart(): Boolean {
        if (phase != ListenPhase.IDLE) return false
        phase = ListenPhase.STARTING; return true
    }

    /** STARTING -> LISTENING. */
    @Synchronized fun finishStart(): Boolean {
        if (phase != ListenPhase.STARTING) return false
        phase = ListenPhase.LISTENING; return true
    }

    /** A start that failed part way goes back to IDLE. */
    @Synchronized fun abortStart() { if (phase == ListenPhase.STARTING) phase = ListenPhase.IDLE }

    /** STARTING or LISTENING -> STOPPING. False if there is nothing to stop. */
    @Synchronized fun beginStop(): Boolean {
        if (phase != ListenPhase.STARTING && phase != ListenPhase.LISTENING) return false
        phase = ListenPhase.STOPPING; return true
    }

    /** STOPPING -> IDLE. */
    @Synchronized fun finishStop() { if (phase == ListenPhase.STOPPING) phase = ListenPhase.IDLE }

    val isListening: Boolean get() = phase == ListenPhase.LISTENING
}
