package com.akashrajeev.voicebeam.cloud

interface UsageStore {
    fun read(): Pair<String, Int>?
    fun write(month: String, seconds: Int)
}

/** Counts cloud seconds used this calendar month. A new month starts at zero. */
class UsageLedger(private val store: UsageStore, private val monthKey: () -> String) {
    @Synchronized fun usedSeconds(): Int {
        val r = store.read() ?: return 0
        return if (r.first == monthKey()) r.second.coerceAtLeast(0) else 0
    }
    @Synchronized fun remainingSeconds(capMinutes: Int): Int = (capMinutes.coerceAtLeast(0) * 60 - usedSeconds()).coerceAtLeast(0)
    @Synchronized fun record(seconds: Int) {
        if (seconds <= 0) return
        store.write(monthKey(), usedSeconds() + seconds)
    }
}
