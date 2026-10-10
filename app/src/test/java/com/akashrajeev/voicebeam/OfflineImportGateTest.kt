package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.OfflineImportGate.Activity
import com.akashrajeev.voicebeam.core.OfflineImportGate.Blocker
import org.junit.Assert.*
import org.junit.Test

class OfflineImportGateTest {
    @Test fun idleIsAllowed() { assertTrue(OfflineImportGate.allowed(Activity())); assertNull(OfflineImportGate.blocker(Activity())) }

    @Test fun eachSingleFlagBlocks() {
        val cases = listOf(
            Activity(recallRecording = true) to Blocker.RECALL_RECORDING,
            Activity(recallBusy = true) to Blocker.RECALL_BUSY,
            Activity(recallAsking = true) to Blocker.RECALL_ASKING,
            Activity(recallRecapping = true) to Blocker.RECALL_RECAPPING,
            Activity(listening = true) to Blocker.LISTENING,
            Activity(recordingActive = true) to Blocker.RECORDING,
            Activity(recordingExporting = true) to Blocker.EXPORTING
        )
        for ((a, b) in cases) { assertFalse(b.name, OfflineImportGate.allowed(a)); assertEquals(b, OfflineImportGate.blocker(a)) }
    }

    @Test fun everyNonEmptyCombinationBlocks() {
        for (mask in 1 until 128) {
            val a = Activity((mask and 1) != 0, (mask and 2) != 0, (mask and 4) != 0, (mask and 8) != 0, (mask and 16) != 0, (mask and 32) != 0, (mask and 64) != 0)
            assertFalse("mask $mask", OfflineImportGate.allowed(a)); assertNotNull(OfflineImportGate.blocker(a))
        }
    }

    @Test fun priorityIsStable() {
        assertEquals(Blocker.RECALL_RECORDING, OfflineImportGate.blocker(Activity(recallRecording = true, recallBusy = true, listening = true)))
        assertEquals(Blocker.LISTENING, OfflineImportGate.blocker(Activity(listening = true, recallBusy = true)))
        assertEquals(Blocker.RECALL_BUSY, OfflineImportGate.blocker(Activity(recallBusy = true, recallAsking = true, recallRecapping = true)))
    }

    @Test fun positionalOverloadMatchesPanelExpression() {
        // same argument order the panel uses: recording, busy, asking, recapping, listening, recording.active, recording.exporting
        assertTrue(OfflineImportGate.allowed(false, false, false, false, false, false, false))
        assertFalse(OfflineImportGate.allowed(false, false, false, true, false, false, false))
        assertFalse(OfflineImportGate.allowed(false, false, false, false, false, false, true))
    }

    @Test fun everyBlockerHasAMessage() { for (b in Blocker.values()) assertTrue(b.message.isNotBlank()) }
}
