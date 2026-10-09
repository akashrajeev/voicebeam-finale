package com.akashrajeev.voicebeam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akashrajeev.voicebeam.engine.VoiceBeamEngine
import kotlin.math.roundToInt

@Composable
fun SetupScreen(engine: VoiceBeamEngine, permsGranted: Boolean, requestPerms: () -> Unit, onStart: () -> Unit) {
    val state by engine.state.collectAsState()
    val settings by engine.settings.collectAsState()
    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(22.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("VoiceBeam", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Tap a face. Hear only them.", fontSize = 20.sp, color = Accent, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Everything runs on this phone. No internet, nothing uploaded.", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))

        CheckRow(
            ok = permsGranted, title = "Camera & microphone",
            value = if (permsGranted) "Allowed" else "Needed",
            action = if (!permsGranted) "Allow" else null, onAction = requestPerms,
        )
        CheckRow(
            ok = state.earphones != null, title = "Earphones",
            value = state.earphones?.let { "$it connected" } ?: "Plug in or pair earphones for live listening",
            action = "Check", onAction = { engine.refreshEarphones() }, icon = true,
        )
        CheckRow(
            ok = state.modelsReady, title = "On-device models",
            value = when {
                state.modelError != null -> "Could not load: ${state.modelError}"
                state.modelsReady -> "Ready (speech, noise, voice)"
                else -> "Loading..."
            },
            loading = !state.modelsReady && state.modelError == null,
        )

        Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Hearing boost", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("+${settings.boostDb.roundToInt()} dB", color = Accent, fontWeight = FontWeight.SemiBold)
            }
            Slider(value = settings.boostDb, onValueChange = { v -> engine.updateSettings { it.copy(boostDb = v) } }, valueRange = 0f..24f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Off", color = Dim, fontSize = 12.sp); Text("Strong", color = Dim, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Caption language", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("English", color = Muted)
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onStart,
            enabled = permsGranted && state.modelsReady,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("start"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) { Text("Start listening", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        if (state.earphones == null) {
            Text("Without earphones VoiceBeam still shows captions, and can record.", color = Dim, fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun CheckRow(ok: Boolean, title: String, value: String, action: String? = null, onAction: () -> Unit = {}, icon: Boolean = false, loading: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Accent)
            ok -> Icon(Icons.Filled.CheckCircle, null, tint = Accent)
            icon -> Icon(Icons.Filled.Headphones, null, tint = Muted)
            else -> Icon(Icons.Filled.ErrorOutline, null, tint = Color(0xFFFFB84D))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(value, color = Muted, fontSize = 13.sp)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action, color = Accent) }
    }
}
