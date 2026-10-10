package com.akashrajeev.voicebeam.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.akashrajeev.voicebeam.diagnostics.StereoMicProbe
import kotlinx.coroutines.*
import java.io.File

@Composable
fun StereoProbePanel(allowed:Boolean,onBusy:(Boolean)->Unit={}) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) }
    val ask=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permission=it}
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var report by remember { mutableStateOf<File?>(File(context.cacheDir,"exports/voicebeam-stereo-probe.txt").takeIf{it.exists()}) }
    LaunchedEffect(allowed) { if(!allowed && busy){status="Cancelled: another feature became active";job?.cancel()} }
    LaunchedEffect(busy){onBusy(busy)}
    DisposableEffect(Unit){onDispose{job?.cancel();onBusy(false)}}
    BackHandler(enabled=busy){job?.cancel();status="Cancelled"}
    SectionHeader("Stereo microphone test")
    Text("Diagnostic only, not direction isolation. About 40 seconds of microphone capture across 8 configurations. No audio saved or uploaded. Report stays on this phone until you choose Share.",color=Muted)
    Text("Keep the phone still. For each 5-second test, speak from its LEFT side first, then RIGHT when prompted. Distinct channels alone do not prove two speakers can be separated.",color=Muted)
    if(!allowed) Text("Stop Listen/Recall recording and wait for processing before testing.",color=Muted)
    if(!permission) TextButton(onClick={ask.launch(Manifest.permission.RECORD_AUDIO)},enabled=!busy){Text("Allow microphone for test")}
    Button(onClick={
        busy=true;status="Starting stereo test";report=null
        job=scope.launch {
            try {
                report=StereoMicProbe.run(context){message->scope.launch { status=message }}
                status="Test complete. Share the text report for analysis."
            }catch(e:CancellationException){if(!status.startsWith("Cancelled"))status="Cancelled";throw e}
            catch(e:Exception){status="Probe failed: ${e.message}"}
            finally{busy=false}
        }
    },enabled=allowed && permission && !busy,modifier=Modifier.testTag("runStereoProbe")){Text("Test stereo microphones")}
    if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick={status="Cancelled";job?.cancel()}){Text("Cancel test")}}
    if(status.isNotBlank())Text(status,color=Muted)
    report?.let { file->TextButton(onClick={shareStereoReport(context,file)},enabled=!busy){Text("Share stereo report")} }
    Spacer(Modifier.height(10.dp))
}
private fun shareStereoReport(context:Context,file:File){
    val uri=FileProvider.getUriForFile(context,context.packageName+".files",file)
    val send=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri(file.name,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
    context.startActivity(Intent.createChooser(send,"Share stereo microphone report"))
}
