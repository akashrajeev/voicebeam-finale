package com.akashrajeev.voicebeam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akashrajeev.voicebeam.core.ProofTelemetry
import java.util.Locale

private fun value(v: Float?): String =
    if (v == null || !v.isFinite()) "n/a" else String.format(Locale.US, "%.3f", v)

/** Read-only technical state. No waveform/native-model reads and no output controls. */
@Composable
fun ProofPanel(snapshot: ProofTelemetry?, stale: Boolean) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF171D24), RoundedCornerShape(12.dp))
        .padding(12.dp).testTag("proofPanel")) {
        Text("Listening evidence (sampled)", color = Color.White, fontSize = 14.sp)
        if (snapshot == null) {
            Text("STUB: live telemetry hook not connected", color = Muted, fontSize = 12.sp)
        } else {
            val t = snapshot
            if (stale) Text("Snapshot stale - not live evidence", color = Muted, fontSize = 12.sp)
            Text("Gate: ${t.gate} | target score: ${value(t.targetProbability)}", color = Accent, fontSize = 12.sp)
            Text("Voice match: ${value(t.voiceMatch)}", color = Color.White, fontSize = 12.sp)
            Text("Denoise mix: ${value(t.actualMix)} | quiet others: ${value(t.quietOthers)}", color = Color.White, fontSize = 12.sp)
            Text("Raw RMS: ${value(t.rawRms)} | digital output RMS: ${value(t.outputRms)}", color = Color.White, fontSize = 12.sp)
            Text("Raw VAD: ${value(t.rawVadProbability)} | mixed-audio VAD: ${value(t.cleanVadProbability)}", color = Color.White, fontSize = 12.sp)
            Text("Playback underruns: ${t.playbackUnderruns ?: "n/a"}", color = Color.White, fontSize = 12.sp)
            Text("Shadow mix: ${value(t.proposedMix)} (not applied)", color = Muted, fontSize = 12.sp)
            Text("Candidate raw floor: ${value(t.candidateRawFloor)} (VAD hypothesis)", color = Muted, fontSize = 12.sp)
        }
        Text("Digital snapshots, not noise-reduction dB or voice-isolation proof.", color = Muted, fontSize = 11.sp)
    }
}
