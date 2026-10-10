package com.akashrajeev.voicebeam.ui

import android.net.Uri
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
import com.akashrajeev.voicebeam.separation.OfflineVideoImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun OfflineVideoPanel(allowed: Boolean, onBusy: (Boolean) -> Unit = {}, onImported: () -> Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var start by remember { mutableStateOf("0") }
    var end by remember { mutableStateOf("3") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    BackHandler(enabled=busy) {}
    LaunchedEffect(busy) { onBusy(busy) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if(selected!=null) { uri=selected; start="0"; end="3"; result="" }
    }
    Column(Modifier.fillMaxWidth().padding(bottom=12.dp)) {
        Text("Custom video - offline isolation", color=Accent)
        Text("1-120 seconds, under 256 MB. Pick 1-10 seconds where only the target speaks. Original stays unchanged. This does not change live listening.", color=Muted)
        TextButton(onClick={ picker.launch(arrayOf("video/*")) },enabled=!busy && allowed,modifier=Modifier.testTag("pickOfflineVideo")) { Text("Choose video") }
        if(!allowed) Text("Stop live listening and recording before offline isolation.",color=Muted)
        if(result.isNotBlank()) Text(result,color=Muted)
    }
    uri?.let {
        AlertDialog(
            onDismissRequest={ if(!busy) uri=null },
            title={ Text("Target-alone reference") },
            text={ Column {
                Text("Watch your original video first. Enter start/end seconds containing only the target voice, no other speaker. Cannot isolate from a face alone. Quality varies, especially overlapping similar voices.")
                OutlinedTextField(start,{start=it},label={Text("Start seconds")},enabled=!busy,singleLine=true,modifier=Modifier.testTag("referenceStart"))
                OutlinedTextField(end,{end=it},label={Text("End seconds")},enabled=!busy,singleLine=true,modifier=Modifier.testTag("referenceEnd"))
                if(busy) { CircularProgressIndicator(); Text("Working locally. Up to 3 minutes. Keep this screen open.") }
                if(result.isNotBlank()) Text(result)
            } },
            confirmButton={ TextButton(onClick={
                val a=start.toDoubleOrNull(); val b=end.toDoubleOrNull()
                if(a==null || b==null || !a.isFinite() || !b.isFinite() || a<0 || b-a !in 1.0..10.0) { result="Choose a valid 1-10 second reference" }
                else {
                    busy=true; result=""
                    scope.launch {
                        try { OfflineVideoImport.run(context,it,a,b); result="Offline isolated MP4 ready. Compare with original; extraction is not guaranteed."; uri=null; onImported() }
                        catch(e:CancellationException) { throw e }
                        catch(e:Exception) { result=e.message ?: "Import failed; original unchanged" }
                        finally { busy=false }
                    }
                }
            },enabled=!busy && allowed,modifier=Modifier.testTag("runOfflineIsolation")) { Text("Isolate offline") } },
            dismissButton={ TextButton(onClick={uri=null},enabled=!busy) {Text("Cancel")} }
        )
    }
}
