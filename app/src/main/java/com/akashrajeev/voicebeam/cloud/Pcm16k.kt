package com.akashrajeev.voicebeam.cloud

/** Float samples at any rate to 16-bit little-endian mono PCM at 16 kHz (linear resample when needed). */
object Pcm16k {
    fun fromFloats(samples: FloatArray, sampleRate: Int): ByteArray {
        val src = if (sampleRate == 16_000 || sampleRate <= 0) samples else {
            val n = (samples.size.toLong() * 16_000 / sampleRate).toInt()
            FloatArray(n) { i ->
                val p = i.toDouble() * sampleRate / 16_000
                val a = p.toInt().coerceAtMost(samples.size - 1)
                val b = (a + 1).coerceAtMost(samples.size - 1)
                (samples[a] + (samples[b] - samples[a]) * (p - a)).toFloat()
            }
        }
        val out = ByteArray(src.size * 2)
        for (i in src.indices) {
            val v = (src[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[2 * i] = (v and 0xff).toByte(); out[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }
}
