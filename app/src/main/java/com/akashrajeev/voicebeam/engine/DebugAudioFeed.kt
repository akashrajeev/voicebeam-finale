package com.akashrajeev.voicebeam.engine

import android.content.Context
import android.util.Log

/**
 * Debug builds only: loops a bundled 16 kHz mono PCM wav as the microphone
 * input, paired with the debug video feed.
 */
class DebugAudioFeed(context: Context) {

    private val samples: FloatArray = try {
        context.assets.open("feed/test_audio.wav").use { ins ->
            val bytes = ins.readBytes()
            val n = (bytes.size - 44) / 2
            FloatArray(n) { i ->
                val lo = bytes[44 + i * 2].toInt() and 0xFF
                val hi = bytes[45 + i * 2].toInt()
                (((hi shl 8) or lo).toShort()) / 32768f
            }
        }
    } catch (t: Throwable) {
        Log.w("VoiceBeamAudio", "debug audio asset missing", t)
        FloatArray(0)
    }

    private var pos = 0

    /** Next [count] samples, looping forever. Silence when the asset is missing. */
    @Synchronized
    fun next(count: Int): FloatArray {
        val out = FloatArray(count)
        if (samples.isEmpty()) return out
        for (i in 0 until count) {
            out[i] = samples[pos]
            pos = (pos + 1) % samples.size
        }
        return out
    }
}
