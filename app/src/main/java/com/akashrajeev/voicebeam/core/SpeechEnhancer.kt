package com.akashrajeev.voicebeam.core

/**
 * One streaming speech enhancer (noise removal / cleanup). Frames go in at a fixed size and cleaned
 * samples come out. An output shorter than the input is allowed (a streaming model can hold back its
 * first frame). An output longer than the input is not.
 *
 * Contract for any provider (on-device model, vendor SDK, relay client):
 *  - [frameShift] equals the pipeline frame size. Adapters buffer internally if their API wants another size.
 *  - [process] never blocks on the network for longer than one frame; slow or failing providers are dropped by [EnhancerSlot].
 *  - Nothing here feeds VAD, speaker match or captions raw taps; those stay on the raw microphone path.
 */
interface SpeechEnhancer {
    val id: String
    val frameShift: Int
    fun reset()
    fun process(frame: FloatArray): FloatArray
    fun release()
}

/**
 * Holds the chosen enhancer plus the local fallback. The pipeline only talks to the slot.
 * The primary is dropped for the rest of the session after repeated errors, bad output (wrong size,
 * NaN/Inf) or repeatedly running slower than real time. The fallback then takes over, and [generation]
 * changes so the caller can realign its raw-sample buffer.
 */
class EnhancerSlot(
    private val fallback: SpeechEnhancer,
    primary: SpeechEnhancer? = null,
    private val clockNs: () -> Long = System::nanoTime,
    private val onEvent: (String) -> Unit = {},
    private val maxFailures: Int = 3,
    private val maxSlowFrames: Int = 8,
    private val sampleRate: Int = 16000,
) {
    private var primary: SpeechEnhancer? = null
    private var failures = 0
    private var slowFrames = 0
    /** Changes every time the active enhancer changes. */
    @Volatile var generation = 0
        private set
    val activeId: String get() = (primary ?: fallback).id
    val usingFallback: Boolean get() = primary == null

    init {
        if (primary != null && primary.frameShift != fallback.frameShift) {
            onEvent("enhancer_rejected id=${primary.id} frameShift=${primary.frameShift} expected=${fallback.frameShift}")
            try { primary.release() } catch (_: Throwable) {}
        } else {
            this.primary = primary
            if (primary != null) onEvent("enhancer_active id=${primary.id}")
        }
    }

    fun reset() {
        val p = primary
        if (p != null) {
            try { p.reset() } catch (t: Throwable) { drop("reset failed: ${t.javaClass.simpleName}") }
        }
        if (primary == null) fallback.reset()
    }

    fun process(frame: FloatArray): FloatArray {
        val p = primary ?: return fallback.process(frame)
        val budgetNs = frame.size * 1_000_000_000L / sampleRate
        val t0 = clockNs()
        val out = try { p.process(frame) } catch (t: Throwable) { null }
        val spent = clockNs() - t0
        if (out == null || out.size > frame.size || !finite(out)) {
            if (++failures >= maxFailures) {
                drop(if (out == null) "errors" else "bad output")
                return fallback.process(frame)
            }
            // Bad frame but not dropped yet: pass the raw frame through for this one frame only.
            return frame.copyOf()
        }
        failures = 0
        if (spent > budgetNs) { if (++slowFrames >= maxSlowFrames) { drop("slower than real time"); return fallback.process(frame) } }
        else slowFrames = 0
        return out
    }

    fun release() {
        primary?.let { try { it.release() } catch (_: Throwable) {} }
        primary = null
    }

    private fun drop(why: String) {
        val p = primary ?: return
        onEvent("enhancer_dropped id=${p.id} reason=$why; falling back to ${fallback.id}")
        primary = null
        try { p.release() } catch (_: Throwable) {}
        try { fallback.reset() } catch (_: Throwable) {}
        generation++
    }

    private fun finite(a: FloatArray): Boolean { for (v in a) if (v.isNaN() || v.isInfinite()) return false; return true }
}

/** Providers register here by id. Only local GTCRN ships; a chosen API adds one factory. */
object EnhancerRegistry {
    private val factories = java.util.concurrent.ConcurrentHashMap<String, () -> SpeechEnhancer?>()
    fun register(id: String, factory: () -> SpeechEnhancer?) { factories[id] = factory }
    fun ids(): Set<String> = factories.keys
    /** Null for the default id, an unknown id, or a factory that fails. The caller then uses the local fallback. */
    fun create(id: String): SpeechEnhancer? {
        if (id == DEFAULT_ID) return null
        return try { factories[id]?.invoke() } catch (_: Throwable) { null }
    }
    const val DEFAULT_ID = "gtcrn"
}
