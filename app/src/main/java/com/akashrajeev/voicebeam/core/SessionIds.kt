package com.akashrajeev.voicebeam.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionIds {
    /**
     * Timestamp id with a numeric suffix when the same second is already taken,
     * so a quick second recording never overwrites the first.
     */
    fun next(now: Date, taken: (String) -> Boolean): String {
        val base = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        if (!taken(base)) return base
        var i = 2
        while (taken("$base-$i")) i++
        return "$base-$i"
    }
}
