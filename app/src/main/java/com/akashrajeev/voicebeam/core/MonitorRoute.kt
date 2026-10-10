package com.akashrajeev.voicebeam.core

/** Android AudioDeviceInfo type values, kept platform-free for route policy tests. */
object MonitorRoute {
    // Wired/USB first, then Bluetooth media. SCO (7) is telephony, not a media sink.
    private val priority = listOf(4, 3, 22, 11, 8, 26)
    fun isMediaHeadphone(type: Int?): Boolean = type in priority
    fun chooseType(available: List<Int>): Int? = priority.firstOrNull { it in available }
}
