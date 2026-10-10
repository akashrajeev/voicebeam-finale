package com.akashrajeev.voicebeam.core

/**
 * Sample-accurate audio/video start alignment for decoded phone footage (pure JVM, testable).
 * offsetUs = audioStartUs - videoStartUs. Audio starting LATER than video gets leading zeros; audio starting EARLIER has its leading samples
 * dropped. The result is on the VIDEO timeline: index 0 is video time 0, so typed reference seconds stay video-timeline seconds.
 */
object AudioAlignment {
    /** Offsets beyond this are treated as a genuinely broken file. */
    const val MAX_OFFSET_US = 2_000_000L

    /** Signed whole samples to pad (+) or trim (-) at [sampleRate]; rounds to nearest. Throws when the offset exceeds the cap. */
    fun leadingSamples(offsetUs: Long, sampleRate: Int): Int {
        require(sampleRate > 0) { "bad sample rate" }
        require(kotlin.math.abs(offsetUs) <= MAX_OFFSET_US) { "This video's audio/video offset is unsupported" }
        return Math.round(offsetUs.toDouble() * sampleRate / 1_000_000.0).toInt()
    }

    fun align(samples: FloatArray, count: Int, sampleRate: Int, offsetUs: Long): FloatArray {
        require(count in 0..samples.size) { "bad sample count" }
        val lead = leadingSamples(offsetUs, sampleRate)
        return if (lead >= 0) {
            val out = FloatArray(lead + count)
            System.arraycopy(samples, 0, out, lead, count)
            out
        } else {
            val drop = minOf(-lead, count)
            samples.copyOfRange(drop, count)
        }
    }
}
