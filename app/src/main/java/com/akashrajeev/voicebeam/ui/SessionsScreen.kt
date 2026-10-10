package com.akashrajeev.voicebeam.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.akashrajeev.voicebeam.Screen
import com.akashrajeev.voicebeam.core.Captions
import com.akashrajeev.voicebeam.engine.SaveMode
import com.akashrajeev.voicebeam.engine.VoiceBeamEngine
import com.akashrajeev.voicebeam.record.SessionMeta
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsScreen(engine: VoiceBeamEngine, onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val list by engine.sessionList.collectAsState()
    val live by engine.state.collectAsState()
    var offlineBusy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<SessionMeta?>(null) }
    var deleting by remember { mutableStateOf<SessionMeta?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    LaunchedEffect(Unit) { engine.refreshSessions() }
    DisposableEffect(Unit) { onDispose { player.value?.release(); player.value = null } }

    fun play(file: File, key: String) {
        player.value?.release(); player.value = null
        if (playing == key) { playing = null; return }
        try {
            player.value = MediaPlayer().apply {
                setDataSource(file.absolutePath); prepare(); start()
                setOnCompletionListener { playing = null }
            }
            playing = key
        } catch (t: Throwable) {
            Toast.makeText(context, "Can't play this file", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.weight(1f).statusBarsPadding().padding(horizontal = 18.dp)) {
            Text("Sessions", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 16.dp))
            OfflineVideoPanel(allowed = !live.listening && !live.recording.active && !live.recording.exporting, onBusy = { offlineBusy = it }, onImported = { engine.refreshSessions() })
            if (list.isEmpty()) {
                Text("No recordings yet. On the Focus screen, lock onto a face and press the red button.", color = Muted)
            }
            LazyColumn(Modifier.testTag("sessionList")) {
                items(list, key = { it.id }) { m ->
                    val segs = remember(m.id, m.title) { engine.sessions.segments(m) }
                    Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == m.id) null else m.id }.padding(vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Card2), contentAlignment = Alignment.Center) {
                                Text(when (m.mode) { SaveMode.AUDIO -> "AUD"; SaveMode.AUDIO_VIDEO -> "A+V"; SaveMode.CAPTIONS -> "¶" }, color = Accent, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(m.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(dateLabel(m.createdAt) + " · " + formatDuration(m.durationMs), color = Muted, fontSize = 12.sp)
                                Text(modeLabel(m), color = Color(0xFFB7BDC4), fontSize = 11.sp)
                            }
                        }
                        val preview = if (expanded == m.id) segs else segs.take(2)
                        preview.forEach { seg ->
                            Text((if (seg.isTarget) "Speaker 1" else "Others (quieted)") + " · " + Captions.clock(seg.startMs),
                                color = if (seg.isTarget) Accent else Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                            Text(seg.text, color = if (seg.isTarget) Color.White else Muted, fontSize = 14.sp)
                        }
                        if (expanded == m.id) {
                            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (File(m.dir, "original.mp4").exists()) Chip("Original", color = Card2) { openFile(context, File(m.dir, "original.mp4"), "video/mp4") }
                                if (m.video.exists()) Chip("▶ Play video", color = Card2) { openFile(context, m.video, "video/mp4") }
                                if (m.cleanAudio.exists()) Chip(if (playing == m.id + "c") "■ Stop" else "▶ Clean", color = Card2) { play(m.cleanAudio, m.id + "c") }
                                if (m.rawWav.exists()) Chip(if (playing == m.id + "r") "■ Stop" else "▶ Raw", color = Card2) { play(m.rawWav, m.id + "r") }
                            }
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip("Export", color = Accent.copy(alpha = 0.2f)) { share(context, m) }
                                Chip("Rename", color = Card2) { renaming = m }
                                Chip("Delete", color = Color(0x33FF4D4F)) { deleting = m }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1F2329)))
                }
            }
        }
        if (!offlineBusy) BottomNav(Screen.SESSIONS, onNavigate)
    }

    renaming?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            confirmButton = { TextButton({ engine.renameSession(m, text.ifBlank { m.title }); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
            title = { Text("Rename") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        )
    }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = { TextButton({ engine.deleteSession(m); deleting = null }) { Text("Delete", color = RecRed) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
            title = { Text("Delete this session?") },
            text = { Text("The recording and transcript will be removed from this phone.") },
        )
    }
}

private fun modeLabel(m: SessionMeta): String = if (File(m.dir, "offline-fallback.txt").exists()) "Original unchanged · isolation check failed" else if (File(m.dir, "offline-reference.txt").exists()) "Offline SpeakerBeam · original retained" else when (m.mode) {
    SaveMode.AUDIO -> "Audio + .srt"
    SaveMode.AUDIO_VIDEO -> "A+V · " + when (m.captions) { "burned" -> "Captions burned in"; "none" -> "No captions"; else -> ".srt file" }
    SaveMode.CAPTIONS -> "Captions only"
}

private fun dateLabel(t: Long): String = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(t))

private fun uriFor(context: Context, f: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)

private fun openFile(context: Context, f: File, mime: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(context, f), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(i) } catch (_: Throwable) { Toast.makeText(context, "No video player found", Toast.LENGTH_SHORT).show() }
}

private fun share(context: Context, m: SessionMeta) {
    val files = listOf(m.video, m.cleanAudio, m.srt, m.txt, m.rawWav).filter { it.exists() && it.length() > 0 }
    if (files.isEmpty()) return
    val uris = ArrayList(files.map { uriFor(context, it) })
    val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        clipData = ClipData.newRawUri(m.title, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(i, "Export " + m.title))
}
