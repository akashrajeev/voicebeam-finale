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
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
    var showConsent by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val dens = LocalDensity.current
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
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { boxSize = it }) {
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
                        drawOval(Accent.copy(alpha = 0.14f), tl - Offset(16f, 16f), GSize(sz.width + 32f, sz.height + 32f), style = Stroke(16f))
                        drawOval(Accent.copy(alpha = 0.30f), tl - Offset(7f, 7f), GSize(sz.width + 14f, sz.height + 14f), style = Stroke(1.5.dp.toPx()))
                        drawOval(Accent, tl, sz, style = Stroke(width = 3.dp.toPx() + f.speaking * 4.dp.toPx()))
                    } else if (f.id == state.consentFaceId && state.consentPhase == com.akashrajeev.voicebeam.core.ConsentPhase.ASKING) {
                        // Asking permission: faint full ring plus a ring that fills while a thumbs-up is held.
                        val grow = Offset(10f, 10f)
                        drawArc(Accent.copy(alpha = 0.25f), -90f, 360f, false, tl - grow, GSize(sz.width + 20f, sz.height + 20f), style = Stroke(8.dp.toPx()))
                        drawArc(Accent, -90f, 360f * state.consentProgress, false, tl - grow, GSize(sz.width + 20f, sz.height + 20f), style = Stroke(8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    } else if (state.lockedId == null) {
                        drawOval(Color.White.copy(alpha = 0.5f), tl, sz, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))))
                    }
                }
            }
            // Shading for legibility (bottom only; the camera view stays normal).
            Box(Modifier.fillMaxWidth().height(240.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))

            // Face positions in view pixels, shared by the tags and the thumbs-up badge.
            val rects = if (boxSize.width > 0 && state.imageWidth > 0 && !state.audioOnly) {
                val iw = state.imageWidth.toFloat(); val ih = state.imageHeight.toFloat()
                val mapper = FillCenterMapper(iw, ih, boxSize.width.toFloat(), boxSize.height.toFloat(), state.mirrored)
                val fitScale = minOf(boxSize.width / iw, boxSize.height / ih)
                val fitOx = (boxSize.width - iw * fitScale) / 2f
                val fitOy = (boxSize.height - ih * fitScale) / 2f
                state.faces.map { f ->
                    val (x1, y1) = if (demoFeed) Pair(fitOx + f.box.left * iw * fitScale, fitOy + f.box.top * ih * fitScale) else mapper.toView(f.box.left, f.box.top)
                    val (x2, y2) = if (demoFeed) Pair(fitOx + f.box.right * iw * fitScale, fitOy + f.box.bottom * ih * fitScale) else mapper.toView(f.box.right, f.box.bottom)
                    val l = minOf(x1, x2); val r = maxOf(x1, x2)
                    val pad = (r - l) * 0.12f
                    Triple(f.id, Offset(l - pad, y1 - pad), GSize(r - l + 2 * pad, y2 - y1 + 2 * pad))
                }
            } else emptyList()
            val margin = with(dens) { 8.dp.toPx() }
            val maxTagX = (boxSize.width - with(dens) { 130.dp.toPx() }).coerceAtLeast(margin)
            val maxTagY = (boxSize.height - with(dens) { 330.dp.toPx() }).coerceAtLeast(margin)
            for ((fid, tl, sz) in rects) {
                val tx = tl.x.coerceIn(margin, maxTagX).roundToInt()
                val ty = (tl.y + sz.height + margin).coerceIn(margin, maxTagY).roundToInt()
                if (fid == state.lockedId) {
                    Row(Modifier.offset { IntOffset(tx, ty) }.clip(RoundedCornerShape(99.dp)).background(Color(0xB3101519))
                        .clickable { showConsent = true }.padding(horizontal = 10.dp, vertical = 5.dp).testTag("lock_tag"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, "locked", tint = Accent, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("You · locked", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else if (state.lockedId != null) {
                    Text("Others", color = Color(0xFFE2E8EC), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.offset { IntOffset(tx, ty) }.clip(RoundedCornerShape(99.dp)).background(Color(0x99101519)).padding(horizontal = 9.dp, vertical = 4.dp))
                }
                if (fid == state.consentFaceId && state.consentPhase == com.akashrajeev.voicebeam.core.ConsentPhase.ASKING) {
                    val bx = (tl.x + sz.width - with(dens) { 20.dp.toPx() }).coerceIn(margin, maxTagX).roundToInt()
                    val by = (tl.y + sz.height - with(dens) { 30.dp.toPx() }).coerceIn(margin, maxTagY).roundToInt()
                    val prog = state.consentProgress
                    Box(Modifier.offset { IntOffset(bx, by) }.size(52.dp).clip(CircleShape).background(Color(0xB3101519)).testTag("thumb_badge"), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val w = 3.dp.toPx()
                            drawArc(Color.White.copy(alpha = 0.2f), -90f, 360f, false, Offset(w / 2, w / 2), GSize(size.width - w, size.height - w), style = Stroke(w))
                            drawArc(Accent, -90f, 360f * prog, false, Offset(w / 2, w / 2), GSize(size.width - w, size.height - w), style = Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        }
                        Icon(Icons.Filled.ThumbUp, "thumbs-up", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }

            // "Locked on you / Consent captured" toast for ~1.8 s after a lock with permission.
            var showCaptured by remember { mutableStateOf(false) }
            LaunchedEffect(state.consentCapturedAtMs) {
                if (state.consentCapturedAtMs > 0L) { showCaptured = true; delay(1800); showCaptured = false }
            }
            val capScale by androidx.compose.animation.core.animateFloatAsState(
                if (showCaptured) 1f else 0.6f,
                androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 300f), label = "capScale")
            val capAlpha by androidx.compose.animation.core.animateFloatAsState(if (showCaptured) 1f else 0f, label = "capAlpha")
            if (capAlpha > 0.01f) {
                Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp)
                    .graphicsLayer { scaleX = capScale; scaleY = capScale; alpha = capAlpha }
                    .background(Color(0xE60F2A24), RoundedCornerShape(26.dp)).padding(horizontal = 18.dp, vertical = 12.dp).testTag("consent_captured")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, "consent captured", tint = Accent, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Locked on you", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Consent captured", color = Color(0xFFBEECDE), fontSize = 12.sp)
                        }
                    }
                }
            }

            // Top bar.
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("Private · on-device", dot = Accent, onClick = { showConsent = true })
                Spacer(Modifier.width(8.dp))
                if (state.recording.active) {
                    Chip("REC " + Captions.clock(nowTick - state.recording.startedAtMs), color = Color(0xCC2A0E0F), dot = RecRed, modifier = Modifier.testTag("rec"))
                }
                Spacer(Modifier.weight(1f))
                Icon(if (state.earphones != null) Icons.Filled.Headphones else Icons.Filled.HeadsetOff, "earphones",
                    tint = if (state.earphones != null) Accent else Muted, modifier = Modifier.padding(8.dp))
                IconButton(onClick = { if (!state.recording.active) backCamera = !backCamera }) { Icon(Icons.Filled.Cameraswitch, "switch camera", tint = Color.White) }
            }

            // Caption card + controls.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                val notice = when {
                    state.wearerEnrollmentActive -> "Learning your voice ${(state.wearerEnrollmentProgress * 100).roundToInt()}%"
                    state.voiceEnrollmentActive -> "Learning their voice ${(state.voiceEnrollmentProgress * 100).roundToInt()}%"
                    state.earphones == null && state.listening && !demoFeed -> "Connect media earphones for live audio"
                    else -> null
                }
                if (notice != null) { Chip(notice, color = Color(0xB3101519)); Spacer(Modifier.height(8.dp)) }
                val asking = state.consentPhase == com.akashrajeev.voicebeam.core.ConsentPhase.ASKING
                val latest = state.partial.ifBlank { state.segments.lastOrNull()?.text ?: "" }
                val latestTarget = if (state.partial.isNotBlank()) state.partialIsTarget else state.segments.lastOrNull()?.isTarget ?: true
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xB3101519)).padding(horizontal = 18.dp, vertical = 14.dp)) {
                    if (asking) {
                        Text("Permission to lock", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text("Hold a thumbs-up by your face", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().testTag("consent_status"), textAlign = TextAlign.Center)
                        Text("or say “I agree”", color = Color(0xFFCED8DE), fontSize = 15.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (latestTarget) Accent else Color(0xFFB0B6BD)))
                            Spacer(Modifier.width(7.dp))
                            Text(if (latestTarget) "You" else "Others", color = if (latestTarget) Accent else Color(0xFFB0B6BD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            if (state.lockedId != null) Text("Others quieted", color = Color(0xFFDDE5EA), fontSize = 11.sp,
                                modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Color(0x22FFFFFF)).padding(horizontal = 9.dp, vertical = 3.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (state.audioError != null) "Audio stopped: ${state.audioError}. Go back and start again."
                            else if (latest.isBlank()) (if (state.lockedId == null) "Tap a face to lock on. Captions appear here." else "Listening...") else latest,
                            color = if (latest.isBlank()) Muted else Color.White,
                            fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 3,
                            modifier = Modifier.testTag("caption"),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (state.audioOnly) {
                    Text("Audio-only listen mode - camera off. Keep earphones connected; the phone microphone still needs to hear the person.", color = Accent, fontSize = 14.sp)
                    Button(onClick = { engine.exitAudioOnly() }) { Text("Back to camera") }
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
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

    if (showConsent) {
        val cState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showConsent = false }, sheetState = cState, containerColor = Card) {
            ConsentSheet(engine, state) { showConsent = false }
        }
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
private fun ConsentSheet(engine: VoiceBeamEngine, state: com.akashrajeev.voicebeam.engine.LiveState, onClose: () -> Unit) {
    val locked = state.lockedId != null
    val asking = state.consentPhase == com.akashrajeev.voicebeam.core.ConsentPhase.ASKING
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text("Consent", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (locked) Accent.copy(alpha = 0.14f) else Card2).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (locked) Icons.Filled.CheckCircle else Icons.Filled.Lock, null, tint = if (locked) Accent else Muted, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (locked) "Permission given" else if (asking) "Waiting for permission" else "No one locked", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Saved on this phone only", color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (locked || asking) androidx.compose.material3.OutlinedButton(onClick = { engine.withdrawConsent(); onClose() },
                modifier = Modifier.weight(1f).testTag("withdraw_consent")) { Text("Withdraw consent", color = RecRed) }
            if (state.consentRecords > 0) androidx.compose.material3.OutlinedButton(onClick = { engine.deleteConsentRecords() },
                modifier = Modifier.weight(1f)) { Text("Delete records (${state.consentRecords})", color = Color.White) }
        }
        if (locked) androidx.compose.material3.TextButton(onClick = { engine.unlock(); onClose() }) { Text("Unlock", color = Muted) }
        Spacer(Modifier.height(16.dp))
        Text("Voice", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (locked) {
            if (state.voiceLearned) Text("Locked voice learned", color = Accent, fontWeight = FontWeight.SemiBold)
            Button(onClick = { engine.beginTargetEnrollment(); onClose() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(if (state.voiceEnrollmentActive) "Restart voice learning" else if (state.voiceLearned) "Learn locked voice again" else "Learn locked voice")
            }
            if (state.voiceEnrollmentActive) Text("Learning ${(state.voiceEnrollmentProgress * 100).roundToInt()}%. The locked person speaks alone.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        } else {
            Text("Lock a face to learn their voice.", color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Learn my voice", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(if (state.wearerEnrollmentActive) "Learning ${(state.wearerEnrollmentProgress * 100).roundToInt()}%. Only you speak." else if (state.wearerLearned) "Learned" else "Optional, helps tell you apart", color = Muted, fontSize = 12.sp)
            }
            if (!state.wearerLearned && !state.wearerEnrollmentActive) androidx.compose.material3.OutlinedButton(onClick = { engine.beginWearerEnrollment(); onClose() }) { Text("Start", color = Accent) }
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
