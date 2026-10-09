package com.akashrajeev.voicebeam.core

/** Bounded all-batch latency histogram, no samples/speech/device identifiers. */
class HearingRuntimeStats {
    var batches=0L;private set
    var maxUs=0L;private set
    var deadlineMisses=0L;private set
    var shortWrites=0L;private set
    var missingWriteSamples=0L;private set
    private val histogram=LongArray(65)
    fun batch(us:Long) {
        require(us>=0)
        batches++;maxUs=maxOf(maxUs,us)
        if(us>=16000)deadlineMisses++
        histogram[(us/500).coerceAtMost(64).toInt()]++
    }
    fun write(written:Int,wanted:Int) {
        if(written in 0 until wanted){shortWrites++;missingWriteSamples+=wanted-written}
    }
    // Bucket upper bound, NOT exact p95. Last bucket is overflow; max also reported.
    fun p95UpperUs():Long {
        if(batches==0L)return 0
        val target=batches-batches/20;var seen=0L
        for(i in histogram.indices){seen+=histogram[i];if(seen>=target)return if(i==64)maxUs else (i+1)*500L}
        return maxUs
    }
}
