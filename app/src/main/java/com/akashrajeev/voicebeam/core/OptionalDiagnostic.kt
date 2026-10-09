package com.akashrajeev.voicebeam.core

/** Optional telemetry must never stop the primary pipeline. A fault disables it for this session. */
class OptionalDiagnostic<T : Any>(
    private val factory: () -> T,
    private val release: (T) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private var instance: T? = null
    private var disabled = false

    fun <R> sample(operation: (T) -> R): R? {
        if (disabled) return null
        return try {
            val model = instance ?: factory().also { instance = it }
            operation(model)
        } catch (t: Throwable) {
            disabled = true
            close()
            try { onFailure(t) } catch (_: Throwable) {}
            null
        }
    }

    fun close() {
        val model = instance
        instance = null
        if (model != null) try { release(model) } catch (_: Throwable) {}
    }
}

/** Keep the in-flight guard until processing ends, including exceptional exits. */
inline fun <T> withCallbackCleanup(cleanup: () -> Unit, process: () -> T): T =
    try { process() } finally { cleanup() }
