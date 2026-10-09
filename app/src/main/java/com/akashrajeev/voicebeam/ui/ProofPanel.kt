package com.akashrajeev.voicebeam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akashrajeev.voicebeam.core.DigitalStateMeter
import com.akashrajeev.voicebeam.engine.LiveState
import com.akashrajeev.voicebeam.engine.Settings
import kotlinx.coroutines.delay
import java.util.Locale

private fun value(v: Float?): String =
    if (v == null || !v.isFinite()) "pending" else String.format(Locale.US, "%.2f", v)

/** Measurement only. No shadow recommendations, model reads, or audio controls. */
@Composable
fun ProofPanel(state: LiveState, settings: Settings) {
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(1000) } }
    val t = state.proofTelemetry
    val fresh = state.listening && t != null && now - t.sampledAtMs in 0..2500
    val live = if (state.listening) "live" else "paused"
    Column(Modifier.fillMaxWidth().background(Color(0xE6171D24), RoundedCornerShape(10.dp))
        .padding(8.dp).testTag("proofPanel")) {
        Text("Digital meter | 15s sampled window", color = Color.White, fontSize = 12.sp)
        Text("Target score ($live): ${if (state.listening) value(state.targetProbability) else "pending"} | voice match: ${if (state.listening) value(state.voiceMatch) else "pending"}", color = Accent, fontSize = 11.sp)
        Text("Settings: denoise ${value(settings.denoise)} | quiet others ${value(settings.quietOthers)}", color = Color.White, fontSize = 11.sp)
        Text("Gate: ${if (fresh) t!!.gate else "pending hook"} | ${if (t == null) "STUB: pipeline hook pending" else if (!fresh) "sample stale" else "sample age ${now-t.sampledAtMs}ms"}", color = Muted, fontSize = 11.sp)
        val readings = DigitalStateMeter.STATES.map { gate ->
            val r = state.digitalMeter.readings.firstOrNull { it.gate == gate }
            val age = r?.lastSampleMs?.let { now - it }
            val valid = state.listening && age != null && age in 0..DigitalStateMeter.WINDOW_MS
            val db = if (valid) r?.ratioDb else null
            val label = gate.name.lowercase(Locale.US)
            val text = if (db != null) String.format(Locale.US,"%+.1fdB",db) else if (t == null) "pending hook" else "pending samples"
            "$label $text (n=${if (valid) r?.count ?: 0 else 0}, age=${if (age != null && age >= 0) "${age/1000}s" else "pending"})"
        }
        readings.forEach { Text(it, color = Color.White, fontSize = 11.sp) }
        Text("RMS raw/output: ${if (fresh) value(t!!.rawRms) + "/" + value(t.outputRms) else "pending hook"} | VAD raw/mixed: ${if (fresh) value(t!!.rawVadProbability) + "/" + value(t.cleanVadProbability) else "pending hook"}", color = Muted, fontSize = 10.sp)
        Text("Underruns: ${if (fresh) t!!.playbackUnderruns ?: "pending" else "pending hook"}", color = Muted, fontSize = 10.sp)
        Text("Sparse 1Hz output/raw energy, includes gain/denoise/boost. Digital, not acoustic or voice separation.", color = Muted, fontSize = 10.sp)
    }
}
