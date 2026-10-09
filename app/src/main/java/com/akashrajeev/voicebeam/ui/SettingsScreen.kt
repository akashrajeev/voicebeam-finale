package com.akashrajeev.voicebeam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.core.content.FileProvider
import com.akashrajeev.voicebeam.engine.Diagnostics
import kotlinx.coroutines.delay
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akashrajeev.voicebeam.BuildConfig
import com.akashrajeev.voicebeam.Screen
import com.akashrajeev.voicebeam.engine.CaptionBurn
import com.akashrajeev.voicebeam.engine.SaveMode
import com.akashrajeev.voicebeam.engine.VoiceBeamEngine
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(engine: VoiceBeamEngine, onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    var diagnosticText by remember { mutableStateOf(Diagnostics.snapshot()) }
    LaunchedEffect(Unit) {
        while (true) { diagnosticText = Diagnostics.snapshot(); delay(1000) }
    }
    val s by engine.settings.collectAsState()
    val state by engine.state.collectAsState()
    Column(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.weight(1f).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Text("Settings", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 16.dp))
            Text("ENH-7: enhancement and turn suppression, not overlap separation", color = Muted, fontSize = 12.sp)
            Text(engine.enrollmentMessage(), color = Muted, fontSize = 12.sp)
            Text("Uncertain voice passes enhanced audio without boost. Only clear other-speaker evidence turns it down.", color = Muted, fontSize = 12.sp)
            SectionHeader("Listening")
            SliderRow("Noise removal", when { s.denoise < 0.05f -> "Off"; s.denoise < 0.6f -> "Light"; else -> "Strong" }, s.denoise, 0f..1f) { v -> engine.updateSettings { it.copy(denoise = v) } }
            SliderRow("Quiet others (default)", "${(s.quietOthers * 100).roundToInt()}%", s.quietOthers, 0f..1f) { v -> engine.updateSettings { it.copy(quietOthers = v) } }
            SliderRow("Hearing boost", "+${s.boostDb.roundToInt()} dB", s.boostDb, 0f..24f) { v -> engine.updateSettings { it.copy(boostDb = v) } }
            SwitchRow("Point mic at the scene", "Uses the camcorder mic setup, best with the back camera", s.useSceneMic) { v -> engine.updateSettings { it.copy(useSceneMic = v) } }
            SectionHeader("Captions")
            ValueRow("Language", "English")
            SwitchRow("Show what others say", "Shown in grey, marked Others", s.showOthersCaptions) { v -> engine.updateSettings { it.copy(showOthersCaptions = v) } }
            Text("Caption size", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Segmented(listOf("Small", "Medium", "Large"), s.captionSize) { i -> engine.updateSettings { it.copy(captionSize = i) } }
            SectionHeader("Saving")
            Text("Default save mode", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            Segmented(listOf("Audio", "Audio + video", "Captions"), s.saveMode.ordinal) { i -> engine.updateSettings { it.copy(saveMode = SaveMode.values()[i]) } }
            Text("Captions on video", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Segmented(listOf("Burned in", ".srt file", "None"), s.captionBurn.ordinal) { i -> engine.updateSettings { it.copy(captionBurn = CaptionBurn.values()[i]) } }
            SwitchRow("Keep raw copy", "Also saves the unprocessed mic audio to compare", s.keepRawAudio) { v -> engine.updateSettings { it.copy(keepRawAudio = v) } }
            SwitchRow("1080p video", "Bigger files; 720p is plenty for most phones", s.hd1080) { v -> engine.updateSettings { it.copy(hd1080 = v) } }
            SectionHeader("Stage captions (PC)")
            SwitchRow("Share captions on local Wi-Fi", state.stageUrl?.let { "Open $it on a laptop" } ?: "Shows big captions in a laptop browser", s.stageEnabled) { v -> engine.updateSettings { it.copy(stageEnabled = v) } }
            if (BuildConfig.DEBUG) {
                SectionHeader("Testing")
                SwitchRow("Demo feed (testing)", "Plays a recorded two-person clip instead of the camera and mic", s.debugFeed) { v -> engine.updateSettings { it.copy(debugFeed = v) } }
            }
            SectionHeader("Optional wearer voice veto")
            Text("Experimental. Speak alone for three clean 3-second phrases. Voice template stays in RAM, clears when models close. This cannot separate overlapping voices.", color = Muted, fontSize = 12.sp)
            Text(if (state.wearerLearned) "Wearer voice learned" else if (state.wearerEnrollmentActive)
                "Learning ${(state.wearerEnrollmentProgress * 100).roundToInt()}% - only you speak"
                else "No wearer template", color = Muted, fontSize = 12.sp)
            Text("Start Learn my voice on the listening screen so the microphone stays active.", color = Muted, fontSize = 12.sp)
            SwitchRow("Veto high-confidence wearer voice", "Off by default. May block a similar target; requires target voice learned too.", state.wearerVetoEnabled) { engine.setWearerVeto(it) }
            Button(onClick = { engine.clearWearerVoice() }) { Text("Clear my voice") }
            SectionHeader("Live diagnostics")
            Text("Local technical logs only. No audio, captions or uploads. Share sends a text file only when you choose an app.", color = Muted, fontSize = 12.sp)
            Row {
                Button(onClick = {
                    val dir = File(context.cacheDir, "exports").also { it.mkdirs() }
                    val file = File(dir, "voicebeam-diagnostics.txt")
                    file.writeText(Diagnostics.snapshot())
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri("VoiceBeam diagnostics", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share VoiceBeam diagnostics"))
                }) { Text("Share log") }
                Button(onClick = { Diagnostics.clear(); diagnosticText = Diagnostics.snapshot() }) { Text("Clear log") }
            }
            Text(diagnosticText.lines().takeLast(12).joinToString("\n"), color = Muted, fontSize = 10.sp)
            SectionHeader("Privacy")
            Text("Speech recognition, noise removal, face tracking and voice matching all run on this phone. VoiceBeam has no account and uploads nothing. The only network use is the optional stage-caption page on your own Wi-Fi.",
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            ValueRow("Version", BuildConfig.VERSION_NAME)
        }
        BottomNav(Screen.SETTINGS, onNavigate)
    }
}

@Composable
private fun SliderRow(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row { Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text(value, color = Muted, fontSize = 13.sp) }
        Slider(v, onChange, valueRange = range)
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Text(sub, color = Muted, fontSize = 12.sp)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
    }
}

@Composable
private fun ValueRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text(value, color = Muted, fontSize = 13.sp)
    }
}
