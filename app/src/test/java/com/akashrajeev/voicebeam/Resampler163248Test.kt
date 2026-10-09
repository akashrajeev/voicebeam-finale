package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.Resampler163248
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class Resampler163248Test {

    private fun identityModel(chunk: FloatArray): FloatArray = chunk

    @Test
    fun processReturns256Samples() {
        val r = Resampler163248()
        val input = FloatArray(256) { (sin(2.0 * PI * 440.0 * it / 16000.0) * 0.5f).toFloat() }
        repeat(50) { r.process(input, ::identityModel) }
        val output = r.process(input, ::identityModel)
        assertEquals(256, output.size)
    }

    @Test
    fun resetClearsStateAndReturnsZeros() {
        val r = Resampler163248()
        val input = FloatArray(256) { 0.2f }
        repeat(50) { r.process(input, ::identityModel) }
        r.reset()
        val output = r.process(FloatArray(256) { 0f }, ::identityModel)
        for (v in output) {
            assertEquals(0f, v, 1e-6f)
        }
    }

    @Test
    fun chunkSize480() {
        var maxSeen = 0
        var minSeen = Int.MAX_VALUE
        val r = Resampler163248()
        val input = FloatArray(256) { (it / 256f).toFloat() }
        repeat(500) {
            r.process(input) { chunk ->
                maxSeen = maxOf(maxSeen, chunk.size)
                minSeen = minOf(minSeen, chunk.size)
                chunk
            }
        }
        assertEquals(480, maxSeen)
        assertEquals(480, minSeen)
    }

    @Test
    fun finiteInputProducesFiniteOutput() {
        val r = Resampler163248()
        val input = FloatArray(256) { it.toFloat() }
        repeat(100) {
            val output = r.process(input, ::identityModel)
            assertTrue(output.all { it.isFinite() })
        }
    }

    @Test
    fun lowFrequencySineSurvivesRoundtrip() {
        val r = Resampler163248()
        val freq = 440.0
        val sr = 16000.0
        val input = FloatArray(256) { (sin(2.0 * PI * freq * it / sr) * 0.5f).toFloat() }
        repeat(50) { r.process(input, ::identityModel) }
        val output = r.process(input, ::identityModel)
        var energy = 0f
        for (v in output) energy += v * v
        assertTrue("Output energy $energy should be > 0.01", energy > 0.01f)
        var inEnergy = 0f
        for (v in input) inEnergy += v * v
        assertTrue("Too much attenuation: in=$inEnergy out=$energy", energy > inEnergy * 0.2f)
    }
}