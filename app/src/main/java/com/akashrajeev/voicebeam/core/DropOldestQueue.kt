package com.akashrajeev.voicebeam.core

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded FIFO queue for the real-time audio thread. Order is always kept.
 * When the consumer falls behind, the oldest item is dropped (and counted) so
 * the producer never blocks and the backlog stays small.
 */
class DropOldestQueue<T : Any>(val capacity: Int) {
    init { require(capacity > 0) }

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val items = ArrayDeque<T>(capacity)
    @Volatile var dropped = 0L
        private set

    val size: Int get() = lock.withLock { items.size }

    /** Adds [item]. Returns true when an older item had to be dropped to make room. */
    fun offer(item: T): Boolean = lock.withLock {
        var droppedOne = false
        if (items.size >= capacity) { items.pollFirst(); dropped++; droppedOne = true }
        items.addLast(item)
        notEmpty.signal()
        droppedOne
    }

    /** Oldest item, waiting up to [timeoutMs]; null on timeout. */
    fun poll(timeoutMs: Long): T? = lock.withLock {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (items.isEmpty()) {
            if (nanos <= 0) return null
            nanos = notEmpty.awaitNanos(nanos)
        }
        items.pollFirst()
    }

    fun clear() = lock.withLock { items.clear() }
}
