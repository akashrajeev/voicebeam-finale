package com.akashrajeev.voicebeam.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.akashrajeev.voicebeam.core.FootageAnalysis
import com.akashrajeev.voicebeam.core.FootageUiText
import com.akashrajeev.voicebeam.core.TapProposal
import com.akashrajeev.voicebeam.core.VideoTapState
import com.akashrajeev.voicebeam.separation.FootageResult
import com.akashrajeev.voicebeam.separation.OfflineFootageImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

@Composable
fun OfflineVideoPanel(
    allowed: Boolean,
    onBusy: (Boolean) -> Unit = {},
    /** Optional face-derived proposal for the CURRENT video (null until a face track exists). Typed input always wins; a proposal needs the user's confirmation. */
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
    LaunchedEffect(allowed) {
        if(!allowed && busy) { importJob?.cancel(); result="Import cancelled because recording or local processing started. Original unchanged." }
    }
    BackHandler(enabled=busy) {}
    LaunchedEffect(busy) { onBusy(busy) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if(selected!=null) {
            uri=selected; start="0"; end="3"; result=""; usedProposal=false; confirmed=false
            durationSec=try {
                val m=android.media.MediaMetadataRetriever()
                try { m.setDataSource(context,selected); (m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:120000L)/1000f } finally { m.release() }
            } catch(e:Exception) { 120f }
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom=12.dp).background(Card, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Custom video - offline isolation", color=Accent, fontWeight=FontWeight.Bold)
        Text("Keep one voice. Compare with the original.", color=androidx.compose.ui.graphics.Color.White)
        FootageUiText.STEPS.forEach { Text(it, color=Muted) }
        Text("Up to 120 seconds / 256 MB. Original stays unchanged. Overlapping voices can still leak.", color=Muted, style=MaterialTheme.typography.bodySmall)
        Button(onClick={ picker.launch(arrayOf("video/*")) },enabled=!busy && allowed,modifier=Modifier.fillMaxWidth().testTag("pickOfflineVideo")) { Text("Choose video") }
        if(!allowed) Text("Stop live listening and recording before offline isolation.",color=Muted)
        if(result.isNotBlank()) Text(result,color=Muted)
    }
    uri?.let {
        val typedA=start.toFloatOrNull(); val typedB=end.toFloatOrNull()
        val typed=if(!usedProposal && typedA!=null && typedB!=null) FootageAnalysis.Interval(typedA,typedB) else null
        val tap=VideoTapState.resolve(if(usedProposal) proposal else null, typed, durationSec, confirmed)
        AlertDialog(
            onDismissRequest={ if(!busy) uri=null },
            title={ Text("Target-alone reference") },
            text={ Column {
                Text("Watch your original video first. Enter start/end seconds containing only the target voice, no other speaker. Use at least 3 seconds when possible. Short or inconsistent references and damaged extraction keep the original unchanged. Cannot isolate from a face alone. Quality varies, especially overlapping similar voices.")
                if(proposal!=null && !usedProposal) {
                    Text(FootageUiText.SUGGESTED_TAP_HINT)
                    TextButton(onClick={ usedProposal=true; confirmed=false; start="%.1f".format(proposal.interval.startSec); end="%.1f".format(proposal.interval.endSec) },enabled=!busy,modifier=Modifier.testTag("useSuggestedMoment")) { Text("Use suggested moment ${"%.1f".format(proposal.interval.startSec)}-${"%.1f".format(proposal.interval.endSec)} s") }
                }
                OutlinedTextField(start,{start=it; usedProposal=false},label={Text("Start seconds")},enabled=!busy,singleLine=true,modifier=Modifier.testTag("referenceStart"))
                OutlinedTextField(end,{end=it; usedProposal=false},label={Text("End seconds")},enabled=!busy,singleLine=true,modifier=Modifier.testTag("referenceEnd"))
                if(tap.source==VideoTapState.Source.PROPOSED || usedProposal) {
                    Text(VideoTapState.resolve(proposal,null,durationSec,confirmed).message)
                    Row { Checkbox(confirmed,{confirmed=it},enabled=!busy,modifier=Modifier.testTag("confirmSuggestedMoment")); Text("I checked this moment: only my target speaks") }
                } else if(!tap.canStart) Text(tap.message)
                if(busy) { CircularProgressIndicator(); Text("Working locally. Up to 3 minutes. Keep this screen open.") }
                if(result.isNotBlank()) Text(result)
            } },
            confirmButton={ TextButton(onClick={
                val a=start.toDoubleOrNull(); val b=end.toDoubleOrNull()
                val ok=if(usedProposal) VideoTapState.resolve(proposal,null,durationSec,confirmed).canStart else tap.canStart
                if(a==null || b==null || !a.isFinite() || !b.isFinite() || a<0 || b-a !in 3.0..10.0 || !ok) { result=if(usedProposal && !confirmed) "Confirm the suggested moment first, or type your own." else if(tap.message.isNotBlank() && !tap.canStart && !usedProposal) tap.message else "Choose a valid 3-10 second reference" }
                else {
                    busy=true; result=""
                    importJob=scope.launch {
                        try {
                            when(val r=OfflineFootageImport.run(context,it,a,b)) {
                                is FootageResult.Done -> { val fallback=java.io.File(r.meta.dir,"offline-fallback.txt"); result=if(fallback.exists()) "Original kept unchanged. " + fallback.readText() else "Video ready. Tap the session below, then Original and Play video to compare. Isolation is not guaranteed."; uri=null; onImported() }
                                is FootageResult.NeedsTap -> result="Original kept unchanged. Choose a 3-10 second moment where only the target speaks (" + r.reason.name + ")."
                            }
                        }
                        catch(e:CancellationException) { throw e }
                        catch(e:Exception) { result=e.message ?: "Import failed; original unchanged" }
                        finally { busy=false }
                    }
                }
            },enabled=!busy && allowed && (if(usedProposal) confirmed else tap.canStart),modifier=Modifier.testTag("runOfflineIsolation")) { Text("Isolate offline") } },
            dismissButton={ TextButton(onClick={uri=null},enabled=!busy) {Text("Cancel")} }
        )
    }
}
