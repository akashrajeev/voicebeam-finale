package com.akashrajeev.voicebeam.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.akashrajeev.voicebeam.core.FootageAnalysis
import com.akashrajeev.voicebeam.core.FootageUiText
import com.akashrajeev.voicebeam.core.TapProposal
import com.akashrajeev.voicebeam.core.VideoTapState
import com.akashrajeev.voicebeam.separation.FootageResult
import com.akashrajeev.voicebeam.separation.OfflineFaceProposal
import com.akashrajeev.voicebeam.separation.OfflineFootageImport
import com.akashrajeev.voicebeam.vision.OfflineFaceFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One upright, downscaled preview frame (normalised face coordinates do not depend on the scale). */
private fun previewFrame(context: android.content.Context, uri: Uri, sec: Float): Bitmap? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(context, uri)
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val f = r.getFrameAtTime((sec * 1000000f).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val m = Matrix()
        if (rot != 0) m.postRotate(rot.toFloat())
        val scale = minOf(1f, 720f / maxOf(f.width, f.height))
        m.postScale(scale, scale)
        val out = Bitmap.createBitmap(f, 0, 0, f.width, f.height, m, true)
        if (out !== f) f.recycle()
        out
    } catch (e: Throwable) { null } finally { try { r.release() } catch (_: Throwable) {} }
}

@Composable
fun OfflineVideoPanel(
    allowed: Boolean,
    onBusy: (Boolean) -> Unit = {},
    /** Optional externally supplied proposal. The panel also computes its own from the face tap; typed input always wins and a proposal needs the user's confirmation. */
    proposal: TapProposal.Proposal? = null,
    onImported: () -> Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var start by remember { mutableStateOf("0") }
    var end by remember { mutableStateOf("3") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var durationSec by remember { mutableStateOf(120f) }
    var usedProposal by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    // face-tap lane (optional: any failure leaves the typed reference as the path)
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var proposeJob by remember { mutableStateOf<Job?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var proposing by remember { mutableStateOf(false) }
    var feed by remember { mutableStateOf<OfflineFaceFeed.Result?>(null) }
    var faceNote by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var previewSec by remember { mutableStateOf(0f) }
    var sliderSec by remember { mutableStateOf(0f) }
    var tapId by remember { mutableStateOf<Int?>(null) }
    var faceProposal by remember { mutableStateOf<TapProposal.Proposal?>(null) }
    val eff = faceProposal ?: proposal
    fun resetFace() { scanJob?.cancel(); proposeJob?.cancel(); scanning=false; proposing=false; feed=null; faceNote=""; preview=null; previewSec=0f; sliderSec=0f; tapId=null; faceProposal=null }
    fun closeDialog() { resetFace(); uri=null }
    LaunchedEffect(allowed) {
        if(!allowed) { scanJob?.cancel(); proposeJob?.cancel(); scanning=false; proposing=false }
        if(!allowed && busy) { importJob?.cancel(); result="Import cancelled because recording or local processing started. Original unchanged." }
    }
    BackHandler(enabled=busy) {}
    LaunchedEffect(busy || scanning || proposing) { onBusy(busy || scanning || proposing) }
    DisposableEffect(Unit) { onDispose { scanJob?.cancel(); proposeJob?.cancel(); onBusy(false) } }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if(selected!=null) {
            resetFace()
            uri=selected; start="0"; end="3"; result=""; usedProposal=false; confirmed=false
            durationSec=try {
                val m=MediaMetadataRetriever()
                try { m.setDataSource(context,selected); (m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:120000L)/1000f } finally { m.release() }
            } catch(e:Exception) { 120f }
            if(allowed) {
                scanning=true; faceNote="Looking for faces on this phone..."
                scanJob=scope.launch {
                    try {
                        val f=withContext(Dispatchers.Default) { OfflineFaceFeed.analyze(context,selected,isCancelled={ !isActive }) }
                        val first=f?.binner?.firstFaceSec()
                        if(f==null || first==null) { faceNote="No faces found here. Type the moment below." }
                        else {
                            val sec=first.coerceIn(0f,durationSec)
                            val bmp=withContext(Dispatchers.IO) { previewFrame(context,selected,sec) }
                            feed=f; previewSec=sec; sliderSec=sec; preview=bmp
                            faceNote=if(bmp==null) "Couldn't show a preview. Type the moment below." else "Tap the person you want to keep. You can scrub to another moment."
                        }
                    } catch(e:CancellationException) { throw e }
                    catch(e:Throwable) { faceNote="Face lookup failed. Type the moment below." }
                    finally { scanning=false }
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom=12.dp).background(Card, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Custom video - offline isolation", color=Accent, fontWeight=FontWeight.Bold)
        Text("Keep one voice. Compare with the original.", color=Color.White)
        FootageUiText.STEPS.forEach { Text(it, color=Muted) }
        Text("Up to 120 seconds / 256 MB. Original stays unchanged. Overlapping voices can still leak.", color=Muted, style=MaterialTheme.typography.bodySmall)
        Button(onClick={ picker.launch(arrayOf("video/*")) },enabled=!busy && allowed,modifier=Modifier.fillMaxWidth().testTag("pickOfflineVideo")) { Text("Choose video") }
        if(!allowed) Text("Stop live listening and recording before offline isolation.",color=Muted)
        if(result.isNotBlank()) Text(result,color=Muted)
    }
    uri?.let {
        val typedA=start.toFloatOrNull(); val typedB=end.toFloatOrNull()
        val typed=if(!usedProposal && typedA!=null && typedB!=null) FootageAnalysis.Interval(typedA,typedB) else null
        val tap=VideoTapState.resolve(if(usedProposal) eff else null, typed, durationSec, confirmed)
        AlertDialog(
            onDismissRequest={ if(!busy) closeDialog() },
            title={ Text("Target-alone reference") },
            text={ Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("Watch your original video first. Enter start/end seconds containing only the target voice, no other speaker. Use at least 3 seconds when possible. Short or inconsistent references and damaged extraction keep the original unchanged. Cannot isolate from a face alone. Quality varies, especially overlapping similar voices.")
                if(scanning) { CircularProgressIndicator(); Text(faceNote) }
                else if(faceNote.isNotBlank()) Text(faceNote)
                val fd=feed; val bmp=preview
                if(fd!=null && bmp!=null) {
                    val faces=fd.binner.facesAt(previewSec)
                    Box(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat()/bmp.height)) {
                        Image(bmp.asImageBitmap(),contentDescription="Video preview",contentScale=ContentScale.FillBounds,modifier=Modifier.matchParentSize())
                        Canvas(Modifier.matchParentSize().testTag("faceTapPreview").pointerInput(previewSec,busy) {
                            detectTapGestures { o ->
                                if(busy || proposing) return@detectTapGestures
                                val id=fd.binner.idAt(o.x/size.width, o.y/size.height, previewSec)
                                if(id==null) { faceNote="That isn't on a face. Tap a face box." }
                                else {
                                    tapId=id; faceProposal=null; usedProposal=false; confirmed=false
                                    proposing=true; faceNote="Looking for a moment where this person speaks alone..."
                                    proposeJob?.cancel()
                                    proposeJob=scope.launch {
                                        try {
                                            val p=OfflineFaceProposal.compute(context,it,fd,id,isCancelled={ !isActive })
                                            faceProposal=p
                                            faceNote=if(p==null) "No clear solo moment for this person. Type one below." else "Found a suggested moment. Check it, then confirm."
                                        } catch(e:CancellationException) { throw e }
                                        catch(e:Throwable) { faceNote="Couldn't suggest a moment. Type one below." }
                                        finally { proposing=false }
                                    }
                                }
                            }
                        }) {
                            faces.forEach { (id,b) ->
                                val sel=id==tapId
                                drawRect(if(sel) Color(0xFF3DDBB0) else Color.White, topLeft=Offset(b.left*size.width,b.top*size.height),
                                    size=Size(b.width*size.width,b.height*size.height), style=Stroke(width=if(sel) 6f else 3f))
                            }
                        }
                    }
                    Slider(sliderSec,{ sliderSec=it },valueRange=0f..maxOf(durationSec,0.1f),enabled=!busy && !proposing,onValueChangeFinished={
                        val target=sliderSec
                        scope.launch {
                            val nb=withContext(Dispatchers.IO) { previewFrame(context,it,target) }
                            if(nb!=null) { preview=nb; previewSec=target }
                        }
                    })
                    Text("Preview at ${"%.1f".format(previewSec)} s",color=Muted,style=MaterialTheme.typography.bodySmall)
                }
                if(proposing) CircularProgressIndicator()
                if(eff!=null && !usedProposal) {
                    Text(FootageUiText.SUGGESTED_TAP_HINT)
                    TextButton(onClick={ usedProposal=true; confirmed=false; start="%.1f".format(eff.interval.startSec); end="%.1f".format(eff.interval.endSec) },enabled=!busy,modifier=Modifier.testTag("useSuggestedMoment")) { Text("Use suggested moment ${"%.1f".format(eff.interval.startSec)}-${"%.1f".format(eff.interval.endSec)} s") }
                }
                OutlinedTextField(start,{start=it; usedProposal=false},label={Text("Start seconds")},enabled=!busy,singleLine=true,modifier=Modifier.testTag("referenceStart"))
                OutlinedTextField(end,{end=it; usedProposal=false},label={Text("End seconds")},enabled=!busy,singleLine=true,modifier=Modifier.testTag("referenceEnd"))
                if(usedProposal) {
                    Text(tap.message)
                    Row { Checkbox(confirmed,{confirmed=it},enabled=!busy,modifier=Modifier.testTag("confirmSuggestedMoment")); Text("I checked this moment: only my target speaks") }
                } else if(!tap.canStart) Text(tap.message)
                if(busy) { CircularProgressIndicator(); Text("Working locally. Up to 3 minutes. Keep this screen open.") }
                if(result.isNotBlank()) Text(result)
            } },
            confirmButton={ TextButton(onClick={
                val a=start.toDoubleOrNull(); val b=end.toDoubleOrNull()
                val ok=tap.canStart
                if(a==null || b==null || !a.isFinite() || !b.isFinite() || a<0 || b-a !in 3.0..10.0 || !ok) { result=if(usedProposal && !confirmed) "Confirm the suggested moment first, or type your own." else if(tap.message.isNotBlank() && !tap.canStart && !usedProposal) tap.message else "Choose a valid 3-10 second reference" }
                else {
                    scanJob?.cancel(); proposeJob?.cancel(); scanning=false; proposing=false
                    busy=true; result=""
                    importJob=scope.launch {
                        try {
                            when(val r=OfflineFootageImport.run(context,it,a,b)) {
                                is FootageResult.Done -> { val fallback=java.io.File(r.meta.dir,"offline-fallback.txt"); result=if(fallback.exists()) "Original kept unchanged. " + fallback.readText() else "Video ready. Tap the session below, then Original and Play video to compare. Isolation is not guaranteed."; closeDialog(); onImported() }
                                is FootageResult.NeedsTap -> result="Original kept unchanged. Choose a 3-10 second moment where only the target speaks (" + r.reason.name + ")."
                            }
                        }
                        catch(e:CancellationException) { throw e }
                        catch(e:Exception) { result=e.message ?: "Import failed; original unchanged" }
                        finally { busy=false }
                    }
                }
            },enabled=!busy && !scanning && !proposing && allowed && tap.canStart,modifier=Modifier.testTag("runOfflineIsolation")) { Text("Isolate offline") } },
            dismissButton={ TextButton(onClick={closeDialog()},enabled=!busy) {Text("Cancel")} }
        )
    }
}
