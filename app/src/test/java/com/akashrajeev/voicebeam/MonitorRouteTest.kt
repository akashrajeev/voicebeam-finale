package com.akashrajeev.voicebeam

import com.akashrajeev.voicebeam.core.MonitorRoute
import org.junit.Assert.*
import org.junit.Test

class MonitorRouteTest {
    @Test fun bluetoothMediaWinsOverScoRegardlessOfEnumeration() {
        assertEquals(8, MonitorRoute.chooseType(listOf(7, 8)))
        assertEquals(8, MonitorRoute.chooseType(listOf(8, 7)))
    }
    @Test fun telephonyRouteCannotReceiveLiveMedia() {
        assertFalse(MonitorRoute.isMediaHeadphone(7))
        assertNull(MonitorRoute.chooseType(listOf(2, 7)))
    }
    @Test fun wiredUsbAndLeRemainSupported() {
        for (type in listOf(3, 4, 11, 22, 26, 8)) assertTrue(MonitorRoute.isMediaHeadphone(type))
        assertEquals(22, MonitorRoute.chooseType(listOf(7, 8, 22)))
    }
    @Test fun unknownAndSpeakerStaySilent() {
        assertFalse(MonitorRoute.isMediaHeadphone(null))
        assertFalse(MonitorRoute.isMediaHeadphone(2))
    }
}
