package com.akashrajeev.voicebeam.core

/** Bounded Moonshine utterances. Keep 1 s pre-roll, decode every 2 s, finalize after 600 ms silence. */
class UtteranceBuffer(private val sampleRate: Int = 16000) {
    data class Update(val samples: FloatArray, val ended: Boolean)
    private val pre = ArrayDeque<Float>()
    private val active = ArrayList<Float>()
    private var silentSamples = 0
    private var lastDecodeSize = 0
    fun accept(samples: FloatArray, speech: Boolean): Update? {
        if (active.isEmpty() && !speech) {
            for (x in samples) { pre.addLast(x); if (pre.size > sampleRate) pre.removeFirst() }
            return null
        }
        if (active.isEmpty()) { active.addAll(pre); pre.clear() }
        for (x in samples) active.add(x)
        silentSamples = if (speech) 0 else silentSamples + samples.size
        val ended = silentSamples >= sampleRate * 0.6f || active.size >= sampleRate * 12
        if (ended || active.size - lastDecodeSize >= sampleRate * 2) {
            val update = Update(active.toFloatArray(), ended)
            lastDecodeSize = active.size
            if (ended) { active.clear(); silentSamples = 0; lastDecodeSize = 0 }
            return update
        }
        return null
    }
    fun reset() { pre.clear(); active.clear(); silentSamples = 0; lastDecodeSize = 0 }
}
