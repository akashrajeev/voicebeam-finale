package com.akashrajeev.voicebeam.ui

import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.view.SurfaceView
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.akashrajeev.voicebeam.BuildConfig
import com.akashrajeev.voicebeam.Screen
import com.akashrajeev.voicebeam.core.FitCenterMapper
import com.akashrajeev.voicebeam.vision.DebugVideoFeed
import com.akashrajeev.voicebeam.vision.FaceSink
import com.akashrajeev.voicebeam.core.Captions
import com.akashrajeev.voicebeam.core.FillCenterMapper
import com.akashrajeev.voicebeam.engine.CaptionBurn
import com.akashrajeev.voicebeam.engine.SaveMode
import com.akashrajeev.voicebeam.engine.VoiceBeamEngine
import com.akashrajeev.voicebeam.vision.FaceAnalyzer
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(engine: VoiceBeamEngine, captionMode: Boolean, onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by engine.state.collectAsState()
    val settings by engine.settings.collectAsState()
    var backCamera by rememberSaveable { mutableStateOf(true) }
    var videoBound by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    val demoFeed = BuildConfig.DEBUG && settings.debugFeed
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val analyzer = remember(demoFeed) { if (demoFeed) null else FaceAnalyzer(context.applicationContext, { t, faces, w, h -> engine.onFaces(t, faces, w, h) }, { engine.gestureIntervalMs() }, { t, hs -> engine.onHands(t, hs) }) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val demoImage = remember(demoFeed) {
        if (!demoFeed) null else ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }
    val demoFeeder = remember(demoFeed) {
        if (!demoFeed) null else DebugVideoFeed(context.applicationContext, FaceSink { t, faces, w, h -> engine.onFaces(t, faces, w, h) }) { bmp ->
            mainHandler.post { demoImage?.setImageBitmap(bmp) }
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    val providerHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    DisposableEffect(Unit) {
        engine.startListening()
        onDispose {
            engine.stopListening()
            executor.shutdown()
        }
    }
    // Keyed on demoFeed: flipping the setting releases the previous path's resources.
    DisposableEffect(demoFeed) {
        onDispose {
            // Unbind first so CameraX stops handing frames to the analyzer before it closes.
            try { providerHolder[0]?.unbindAll() } catch (_: Throwable) {}
            demoFeeder?.stop()
            executor.execute { analyzer?.close() }
        }
    }
    LaunchedEffect(state.modelsReady) { if (state.modelsReady) engine.startListening() }

    LaunchedEffect(backCamera, settings.hd1080, demoFeed, state.audioOnly) {
        if (state.audioOnly) {
            try { providerHolder[0]?.unbindAll() } catch (_: Throwable) {}
            engine.clearVideoCapture()
            videoBound = false
            demoFeeder?.stop()
            return@LaunchedEffect
        }
        if (demoFeed) {
            try { providerHolder[0]?.unbindAll() } catch (_: Throwable) {}
            engine.setMirrored(false)
            videoBound = false
            demoFeeder?.start()
            return@LaunchedEffect
        }
        val provider = ProcessCameraProvider.getInstance(context).let { f -> kotlinx.coroutines.suspendCancellableCoroutine<ProcessCameraProvider> { c -> f.addListener({ c.resume(f.get()) { } }, ContextCompat.getMainExecutor(context)) } }
        providerHolder[0] = provider
        val selector = if (backCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        engine.setMirrored(!backCamera)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build().also { a -> analyzer?.let { an -> a.setAnalyzer(executor, an) } }
        provider.unbindAll()
        videoBound = try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis, engine.buildVideoCapture())
            true
        } catch (t: Throwable) {
            Log.w("VoiceBeamUI", "3 use cases not supported, binding without video", t)
            engine.clearVideoCapture()
            provider.unbindAll()
            try { provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis) } catch (t2: Throwable) { Log.e("VoiceBeamUI", "camera bind failed", t2) }
            false
        }
    }

    // Ticking clock for the REC badge.
    var nowTick by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.recording.active) {
        while (state.recording.active) { nowTick = android.os.SystemClock.elapsedRealtime(); delay(500) }
    }
    LaunchedEffect(state.recording.lastMessage) {
        if (state.recording.lastMessage != null && !state.recording.exporting) { delay(3500); engine.dismissRecordingMessage() }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // key() forces AndroidView to recreate when the feed flips; its factory
            // lambda only runs once, so without it the camera preview stays attached.
            if (!state.audioOnly) key(demoFeed) {
                AndroidView({ if (demoFeed) demoImage ?: previewView else previewView }, Modifier.fillMaxSize())
            }
            // Face rings + tap to lock. No camera frames are needed after enrollment.
            if (!state.audioOnly) Canvas(
                Modifier.fillMaxSize().testTag("faces").pointerInput(state.imageWidth, state.imageHeight, state.mirrored) {
                    detectTapGestures { pos ->
                        if (state.imageWidth > 0) {
                            val (nx, ny) = if (demoFeed)
                                FitCenterMapper(state.imageWidth.toFloat(), state.imageHeight.toFloat(), size.width.toFloat(), size.height.toFloat(), state.mirrored).toImage(pos.x, pos.y)
                            else
                                FillCenterMapper(state.imageWidth.toFloat(), state.imageHeight.toFloat(), size.width.toFloat(), size.height.toFloat(), state.mirrored).toImage(pos.x, pos.y)
                            engine.lockAt(nx, ny)
                        }
                    }
                }
            ) {
                if (state.imageWidth <= 0) return@Canvas
                val iw = state.imageWidth.toFloat()
                val ih = state.imageHeight.toFloat()
                val mapper = FillCenterMapper(iw, ih, size.width, size.height, state.mirrored)
                val fitScale = minOf(size.width / iw, size.height / ih)
                val fitOx = (size.width - iw * fitScale) / 2f
                val fitOy = (size.height - ih * fitScale) / 2f
                for (f in state.faces) {
                    // Demo feed is shown letterboxed and never mirrored; the camera fills the view.
                    val (x1, y1) = if (demoFeed) Pair(fitOx + f.box.left * iw * fitScale, fitOy + f.box.top * ih * fitScale) else mapper.toView(f.box.left, f.box.top)
                    val (x2, y2) = if (demoFeed) Pair(fitOx + f.box.right * iw * fitScale, fitOy + f.box.bottom * ih * fitScale) else mapper.toView(f.box.right, f.box.bottom)
                    val l = minOf(x1, x2); val r = maxOf(x1, x2)
                    val pad = (r - l) * 0.12f
                    val tl = Offset(l - pad, y1 - pad); val sz = GSize(r - l + 2 * pad, y2 - y1 + 2 * pad)
                    if (f.id == state.lockedId) {
                        drawOval(Accent.copy(alpha = 0.10f), tl - Offset(14f, 14f), GSize(sz.width + 28f, sz.height + 28f), style = Stroke(14f))
                        drawOval(Accent, tl, sz, style = Stroke(width = 3.dp.toPx() + f.speaking * 4.dp.toPx()))
                    } else if (f.id == state.consentFaceId && state.consentPhase == com.akashrajeev.voicebeam.core.ConsentPhase.ASKING) {
                        // Asking permission: faint full ring plus a ring that fills while a thumbs-up is held.
                        val grow = Offset(10f, 10f)
                        drawArc(Accent.copy(alpha = 0.25f), -90f, 360f, false, tl - grow, GSize(sz.width + 20f, sz.height + 20f), style = Stroke(8.dp.toPx()))
                        drawArc(Accent, -90f, 360f * state.consentProgress, false, tl - grow, GSize(sz.width + 20f, sz.height + 20f), style = Stroke(8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    } else {
                        drawOval(Color.White.copy(alpha = 0.5f), tl, sz, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))))
                    }
                }
            }
            // Shading for legibility.
            Box(Modifier.fillMaxWidth().height(260.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))))

            // "Consent captured" pop-up for ~1.8 s after a lock with permission.
            var showCaptured by remember { mutableStateOf(false) }
            LaunchedEffect(state.consentCapturedAtMs) {
                if (state.consentCapturedAtMs > 0L) { showCaptured = true; delay(1800); showCaptured = false }
            }
            val capScale by androidx.compose.animation.core.animateFloatAsState(
                if (showCaptured) 1f else 0.5f,
                androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 300f), label = "capScale")
            val capAlpha by androidx.compose.animation.core.animateFloatAsState(if (showCaptured) 1f else 0f, label = "capAlpha")
            if (capAlpha > 0.01f) {
                Box(Modifier.align(Alignment.Center).graphicsLayer { scaleX = capScale; scaleY = capScale; alpha = capAlpha }
                    .background(Color(0xE6101519), RoundedCornerShape(20.dp)).padding(horizontal = 24.dp, vertical = 16.dp).testTag("consent_captured")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, "consent captured", tint = Accent, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Consent captured", color = Color.White, fontSize = 20.sp)
                    }
                }
            }

            // Top bar.
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("On-device · offline", dot = Accent)
                Spacer(Modifier.width(8.dp))
                if (state.recording.active) {
                    Chip("REC " + Captions.clock(nowTick - state.recording.startedAtMs), color = Color(0xCC2A0E0F), dot = RecRed, modifier = Modifier.testTag("rec"))
                }
                Spacer(Modifier.weight(1f))
                Icon(if (state.earphones != null) Icons.Filled.Headphones else Icons.Filled.HeadsetOff, "earphones",
                    tint = if (state.earphones != null) Accent else Muted, modifier = Modifier.padding(8.dp))
                IconButton(onClick = { if (!state.recording.active) backCamera = !backCamera }) { Icon(Icons.Filled.Cameraswitch, "switch camera", tint = Color.White) }
            }

            // Status + caption + controls.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                Column(Modifier.fillMaxWidth().background(Color(0xE6101519), RoundedCornerShape(12.dp)).padding(10.dp)) {
                    Text("ENH-6 | Enhancement, not overlapping-voice separation", color = Color.White, fontSize = 12.sp)
                    Text(engine.enrollmentMessage(), color = Color.White, fontSize = 12.sp)
                }
                StatusLine(state.lockedId != null, state.lockedSpeaking, state.voiceLearned, state.voiceMatch) { engine.unlock() }
                if (state.consentMessage.isNotEmpty() || state.lockedId != null) {
                    Text(
                        when {
                            state.consentPhase == com.akashrajeev.voicebeam.core.ConsentPhase.ASKING -> "Asking permission: " + state.consentMessage
                            state.lockedId != null -> "Consent given. " + state.consentMessage + " (mouth movement match, not identity proof)"
                            else -> state.consentMessage
                        },
                        color = Color.White, fontSize = 12.sp, modifier = Modifier.testTag("consent_status"),
                    )
                }
                if (state.lockedId != null || state.consentPhase == com.akashrajeev.voicebeam.core.ConsentPhase.ASKING) {
                    Button(onClick = { engine.withdrawConsent() }, modifier = Modifier.testTag("withdraw_consent")) { Text("Withdraw consent") }
                }
                if (state.consentRecords > 0) {
                    androidx.compose.material3.TextButton(onClick = { engine.deleteConsentRecords() }) { Text("Delete consent records (${state.consentRecords})", fontSize = 12.sp) }
                }
                if (state.wearerEnrollmentActive) {
                    Text("Learning YOUR voice ${(state.wearerEnrollmentProgress * 100).roundToInt()}%: only you speak. Target learning paused.", color = Muted, fontSize = 12.sp)
                }
                if (!state.wearerLearned && !state.wearerEnrollmentActive && !state.voiceEnrollmentActive) {
                    Button(onClick = { engine.beginWearerEnrollment() }) { Text("Learn my voice (optional)") }
                }
                if (state.lockedId != null && !state.voiceLearned && !state.wearerEnrollmentActive) {
                    Text(if (state.voiceEnrollmentActive)
                        "Learning ${(state.voiceEnrollmentProgress * 100).roundToInt()}%: only the target speaks. Capture 9 seconds; pauses and lip dips will not reset it."
                        else "Face locked, voice not learned. Ask the target to speak alone, then start voice learning.", color = Muted, fontSize = 12.sp)
                    Button(onClick = { engine.beginTargetEnrollment() }) {
                        Text(if (state.voiceEnrollmentActive) "Restart voice learning" else "Learn locked voice")
                    }
                }
                if (state.earphones == null && state.listening && !demoFeed) {
                    Text("Live audio needs a media earphone route (Bluetooth Media audio or wired/USB). Call-only SCO audio is not supported.", color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                val latest = state.partial.ifBlank { state.segments.lastOrNull()?.text ?: "" }
                val latestTarget = if (state.partial.isNotBlank()) state.partialIsTarget else state.segments.lastOrNull()?.isTarget ?: true
                Text(
                    if (state.audioError != null) "Audio stopped: ${state.audioError}. Go back and start again."
                    else if (latest.isBlank()) (if (state.lockedId == null) "Tap a face to lock on. Captions appear here." else "Listening...") else latest,
                    color = if (latest.isBlank()) Muted else if (latestTarget) Color.White else Color(0xFFB0B6BD),
                    fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 3,
                    modifier = Modifier.testTag("caption"),
                )
                Spacer(Modifier.height(12.dp))
                if (state.audioOnly) {
                    Text("Audio-only listen mode - camera off. Keep earphones connected; the phone microphone still needs to hear the person.", color = Accent, fontSize = 14.sp)
                    Button(onClick = { engine.exitAudioOnly() }) { Text("Back to camera") }
                } else if (state.lockedId != null) {
                    Text("Camera-free listening is not yet reliable across speakers. Keep the camera on for now.", color = Muted, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quiet others", color = Muted, fontSize = 13.sp)
                    Slider(settings.quietOthers, { v -> engine.updateSettings { it.copy(quietOthers = v) } }, Modifier.weight(1f).padding(horizontal = 10.dp))
                    Text("${(settings.quietOthers * 100).roundToInt()}%", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Modifier.testTag("captionMode"), { onNavigate(Screen.CAPTIONS) }) { Icon(Icons.Filled.TextFields, "caption mode", tint = Color.White) }
                    RecordButton(state.recording.active, state.recording.exporting) {
                        if (state.recording.active) engine.stopRecording()
                        else if (!state.recording.exporting) engine.startRecording(settings.saveMode, videoBound)
                    }
                    RoundButton(Modifier.testTag("saveMode"), { if (!state.recording.active) showSheet = true }) {
                        Text(when (settings.saveMode) { SaveMode.AUDIO -> "AUD"; SaveMode.AUDIO_VIDEO -> "A+V"; SaveMode.CAPTIONS -> "CC" },
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
            if (demoFeed) {
                Chip("Demo feed - recorded test clip", Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp, start = 16.dp, end = 16.dp), color = Card2)
            }
            if (analyzer?.available == false || demoFeeder?.available == false) {
                Chip("Face tracking isn't available on this device. Captions and noise removal still work.",
                    Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 100.dp, start = 16.dp, end = 16.dp), color = Card2)
            }
            state.recording.lastMessage?.let {
                Chip(it, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = if (demoFeed) 100.dp else 64.dp), color = Card2)
            }
        }
        BottomNav(Screen.FOCUS, onNavigate)
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState, containerColor = Card) {
            SaveSheet(engine, videoBound) {
                showSheet = false
                engine.startRecording(engine.settings.value.saveMode, videoBound)
            }
        }
    }
}

@Composable
private fun StatusLine(locked: Boolean, speaking: Float, learned: Boolean, match: Float?, onUnlock: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (locked) {
            Chip(if (speaking > 0.35f) "Speaking · locked" else "Locked", color = Accent.copy(alpha = 0.22f), dot = Accent)
            Spacer(Modifier.width(8.dp))
            Chip(if (learned) "Voice learned" + (match?.let { " · ${(it * 100).roundToInt()}%" } ?: "") else "Voice not learned", color = Card)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.LockOpen, "unlock", tint = Muted, modifier = Modifier.clip(CircleShape).clickable(onClick = onUnlock).padding(6.dp))
        } else {
            Chip("No one locked · hearing everyone", color = Card)
        }
    }
}

@Composable
private fun RoundButton(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier.size(54.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun RecordButton(active: Boolean, exporting: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).clickable(onClick = onClick).testTag("record"),
        contentAlignment = Alignment.Center,
    ) {
        if (active) Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(RecRed))
        else Box(Modifier.size(58.dp).clip(CircleShape).background(if (exporting) Dim else RecRed))
    }
}

@Composable
private fun SaveSheet(engine: VoiceBeamEngine, videoBound: Boolean, onRecord: () -> Unit) {
    val s by engine.settings.collectAsState()
    val freeGb = engine.sessions.freeBytes() / 1_000_000_000.0
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text("Start recording", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Saves the cleaned voice of the locked person", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        ModeOption(s.saveMode == SaveMode.AUDIO, "Audio only", "Clean voice .m4a + transcript", "~1 MB/min") { engine.updateSettings { it.copy(saveMode = SaveMode.AUDIO) } }
        ModeOption(s.saveMode == SaveMode.AUDIO_VIDEO, "Audio + video", if (videoBound) "Camera video with the clean voice" else "Camera can't record video here; audio will be saved", if (s.hd1080) "~120 MB/min" else "~60 MB/min") {
            engine.updateSettings { it.copy(saveMode = SaveMode.AUDIO_VIDEO) }
        }
        if (s.saveMode == SaveMode.AUDIO_VIDEO) {
            SectionHeader("Captions")
            Segmented(listOf("Burned in", ".srt file", "None"), when (s.captionBurn) { CaptionBurn.BURNED -> 0; CaptionBurn.SRT -> 1; CaptionBurn.NONE -> 2 }) { i ->
                engine.updateSettings { it.copy(captionBurn = listOf(CaptionBurn.BURNED, CaptionBurn.SRT, CaptionBurn.NONE)[i]) }
            }
            SectionHeader("Quality")
            Segmented(listOf("720p", "1080p"), if (s.hd1080) 1 else 0) { i -> engine.updateSettings { it.copy(hd1080 = i == 1) } }
            Spacer(Modifier.height(10.dp))
        }
        ModeOption(s.saveMode == SaveMode.CAPTIONS, "Captions only", "Text transcript, no media", "tiny") { engine.updateSettings { it.copy(saveMode = SaveMode.CAPTIONS) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Free space: %.0f GB".format(freeGb), color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("Keep raw copy", color = Muted, fontSize = 12.sp)
            Checkbox(s.keepRawAudio, { v -> engine.updateSettings { it.copy(keepRawAudio = v) } }, colors = CheckboxDefaults.colors(checkedColor = Accent))
        }
        Text("Ask before recording people. A red REC badge stays on screen the whole time.", color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(RecRed).clickable(onClick = onRecord).testTag("sheetRecord"),
            contentAlignment = Alignment.Center,
        ) { Text("● Record", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, sub: String, size: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Accent.copy(alpha = 0.12f) else Card2)
            .border(if (selected) 1.5.dp else 0.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).border(if (selected) 5.dp else 2.dp, if (selected) Accent else Dim, CircleShape))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Muted, fontSize = 12.sp)
        }
        Text(size, color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card2).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (i == selected) Color(0xFF3A414B) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (i == selected) Color.White else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
        }
    }
}
