package com.akashrajeev.voicebeam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akashrajeev.voicebeam.engine.VoiceBeamEngine

/** Phone flat on the table: huge captions of the locked speaker. */
@Composable
fun CaptionScreen(engine: VoiceBeamEngine, onBack: () -> Unit) {
    val state by engine.state.collectAsState()
    val settings by engine.settings.collectAsState()
    var showPc by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val base = when (settings.captionSize) { 0 -> 24; 2 -> 40; else -> 31 }
    val lines = state.segments.filter { settings.showOthersCaptions || it.isTarget }.takeLast(30)
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, state.partial) { listState.scrollToItem(maxOf(0, lines.size)) }

    Column(Modifier.fillMaxSize().background(Color(0xFF050607)).safeDrawingPadding().padding(20.dp).testTag("captionScreen")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(if (state.lockedId != null) "Locked: Speaker 1" else "Everyone", color = Accent.copy(alpha = 0.18f), dot = Accent)
            if (state.recording.active) { Spacer(Modifier.padding(4.dp)); Chip("REC", color = Color(0xCC2A0E0F), dot = RecRed) }
            Spacer(Modifier.weight(1f))
            Chip("A−", color = Card2) { engine.updateSettings { it.copy(captionSize = (it.captionSize - 1).coerceAtLeast(0)) } }
            Spacer(Modifier.padding(4.dp))
            Chip("A+", color = Card2) { engine.updateSettings { it.copy(captionSize = (it.captionSize + 1).coerceAtMost(2)) } }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.Bottom) {
            items(lines) { seg ->
                Text(
                    (if (seg.isTarget) "" else "Others: ") + seg.text,
                    color = if (seg.isTarget) Color(0xFF9AA1A9) else Color(0xFF5E656E),
                    fontStyle = if (seg.isTarget) FontStyle.Normal else FontStyle.Italic,
                    fontSize = (base * 0.72f).sp, lineHeight = (base * 0.95f).sp,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            item {
                Text(
                    state.partial.ifBlank { if (lines.isEmpty()) "Waiting for speech..." else "" },
                    color = if (state.partial.isBlank()) Dim else if (state.partialIsTarget) Color.White else Color(0xFFB0B6BD),
                    fontSize = base.sp, lineHeight = (base * 1.22f).sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Show on PC", Modifier.weight(1f)) {
                if (!settings.stageEnabled) engine.updateSettings { it.copy(stageEnabled = true) } else engine.refreshStageUrl()
                showPc = true
            }
            BigButton("Back to camera", Modifier.weight(1f), accent = true, onClick = onBack)
        }
    }
    if (showPc) {
        AlertDialog(
            onDismissRequest = { showPc = false },
            confirmButton = { TextButton({ showPc = false }) { Text("Done") } },
            dismissButton = { TextButton({ engine.updateSettings { it.copy(stageEnabled = false) }; showPc = false }) { Text("Turn off") } },
            title = { Text("Stage captions on a laptop") },
            text = {
                Column {
                    Text("Put the laptop on the same Wi-Fi or hotspot as this phone, then open:")
                    Spacer(Modifier.height(10.dp))
                    Text(state.stageUrl ?: "Starting...", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("stageUrl"))
                    Spacer(Modifier.height(10.dp))
                    Text("Captions stay on your local network. Nothing goes to the internet.", color = Muted, fontSize = 13.sp)
                }
            },
        )
    }
}

@Composable
private fun BigButton(text: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.height(54.dp).clip(RoundedCornerShape(16.dp)).background(if (accent) Accent else Card2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (accent) Color(0xFF00241A) else Color.White, fontWeight = FontWeight.Bold) }
}
