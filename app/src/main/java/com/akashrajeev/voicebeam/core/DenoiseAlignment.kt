package com.akashrajeev.voicebeam.core

/** Keep raw samples until the streaming denoiser returns their corresponding output. */
class DenoiseAlignment(capacity: Int = 4096) {
    private val raw = FloatArray(capacity)
    private var head = 0
    private var size = 0
    fun reset() { head = 0; size = 0 }
    fun push(input: FloatArray) {
        check(size + input.size <= raw.size) { "Denoiser raw alignment buffer overflow" }
        for (sample in input) { raw[(head + size) % raw.size] = sample; size++ }
    }
    fun mix(denoised: FloatArray, mix: Float, out: FloatArray, n: Int) {
        require(n <= size && n <= denoised.size && n <= out.size)
        for (k in 0 until n) {
            out[k] = mix * denoised[k] + (1f - mix) * raw[head]
            head = (head + 1) % raw.size; size--
        }
    }
}
