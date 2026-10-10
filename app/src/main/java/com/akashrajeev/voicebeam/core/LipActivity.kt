package com.akashrajeev.voicebeam.core

import kotlin.math.sqrt

/**
 * Turns a stream of mouth-openness values (lip gap / face height) into a 0..1
 * "is talking" score. Talking makes the mouth open and close several times a
 * second, so we look at how much the openness moves inside a short window.
 */
class LipActivity(
    private val windowMs: Long = 700,
    private val quietStd: Float = 0.006f,
    private val loudStd: Float = 0.030f,
) {
    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Float>()

    fun add(timeMs: Long, openness: Float) {
        times.addLast(timeMs)
        values.addLast(openness)
        while (times.isNotEmpty() && timeMs - times.first() > windowMs) {
            times.removeFirst()
            values.removeFirst()
        }
    }

    /** 0 = still mouth, 1 = clearly talking. */
    fun score(): Float {
        if (values.size < 4) return 0f
        val mean = values.sum() / values.size
        var acc = 0f
        for (v in values) acc += (v - mean) * (v - mean)
        val std = sqrt(acc / values.size)
        // Also reward frame-to-frame motion, which is what speech looks like.
        var motion = 0f
        var prev = values.first()
        for (v in values) { motion += kotlin.math.abs(v - prev); prev = v }
        val motionPerFrame = motion / (values.size - 1)
        val a = ((std - quietStd) / (loudStd - quietStd)).coerceIn(0f, 1f)
        val b = ((motionPerFrame - quietStd * 0.6f) / (loudStd * 0.6f)).coerceIn(0f, 1f)
        return (0.6f * a + 0.4f * b).coerceIn(0f, 1f)
    }

    fun clear() { times.clear(); values.clear() }
}
