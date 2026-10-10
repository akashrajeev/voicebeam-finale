package com.akashrajeev.voicebeam.core

/** Offline reference must be target alone, a waveform, not a face or embedding. */
object ReferenceInterval {
    fun slice(samples: FloatArray, startSeconds: Double, endSeconds: Double): FloatArray {
        require(startSeconds.isFinite() && endSeconds.isFinite()) { "Enter valid seconds" }
        require(startSeconds >= 0 && endSeconds-startSeconds in 1.0..10.0) { "Choose 1-10 seconds with only the target speaking" }
        val a=(startSeconds*16000).toInt(); val b=(endSeconds*16000).toInt()
        require(b<=samples.size && a<b) { "Reference interval is outside the video" }
        return samples.copyOfRange(a,b).also { require(ExtractionRate.valid(it)) { "Reference is silent or invalid" } }
    }
}
