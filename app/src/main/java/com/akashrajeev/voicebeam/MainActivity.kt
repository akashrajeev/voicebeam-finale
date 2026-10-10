package com.akashrajeev.voicebeam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.akashrajeev.voicebeam.engine.VoiceBeamEngine
import com.akashrajeev.voicebeam.ui.CaptionScreen
import com.akashrajeev.voicebeam.ui.FocusScreen
import com.akashrajeev.voicebeam.ui.SessionsScreen
import com.akashrajeev.voicebeam.ui.SettingsScreen
import com.akashrajeev.voicebeam.ui.SetupScreen
import com.akashrajeev.voicebeam.ui.VoiceBeamTheme

enum class Screen { SETUP, FOCUS, CAPTIONS, SESSIONS, SETTINGS, RECALL, REMINDERS }

class MainActivity : ComponentActivity() {
    private val engine: VoiceBeamEngine get() = (application as VoiceBeamApp).engine
    private var permsGranted by mutableStateOf(false)

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permsGranted = hasPerms()
    }

    private fun hasPerms() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        permsGranted = hasPerms()
        engine.loadModels()
        setContent {
            VoiceBeamTheme {
                App(engine, permsGranted) { askPerms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permsGranted = hasPerms()
        engine.refreshEarphones()
    }

    override fun onDestroy() {
        if (isFinishing) engine.releaseModels()
        super.onDestroy()
    }
}

@Composable
fun App(engine: VoiceBeamEngine, permsGranted: Boolean, requestPerms: () -> Unit) {
    val settings by engine.settings.collectAsState()
    var screen by rememberSaveable { mutableStateOf(if (settings.onboarded && permsGranted) Screen.FOCUS else Screen.SETUP) }
    LaunchedEffect(permsGranted) { if (!permsGranted) screen = Screen.SETUP }
    BackHandler(enabled = screen != Screen.SETUP && screen != Screen.FOCUS) { screen = Screen.FOCUS }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(screen) {
        if(screen == Screen.FOCUS || screen == Screen.CAPTIONS) {
            val recall = (context.applicationContext as VoiceBeamApp).recall
            if(recall.state.value.recording) context.startService(android.content.Intent(context, com.akashrajeev.voicebeam.recall.RecallRecorderService::class.java).setAction(com.akashrajeev.voicebeam.recall.RecallRecorderService.STOP))
        }
    }
    when (screen) {
        Screen.SETUP -> SetupScreen(engine, permsGranted, requestPerms) {
            engine.updateSettings { it.copy(onboarded = true) }
            screen = Screen.FOCUS
        }
        Screen.FOCUS, Screen.CAPTIONS -> {
            // The camera stays bound across both views so the lock and recording keep going.
            FocusScreen(engine, captionMode = screen == Screen.CAPTIONS, onNavigate = { screen = it })
            if (screen == Screen.CAPTIONS) CaptionScreen(engine) { screen = Screen.FOCUS }
        }
        Screen.SESSIONS -> SessionsScreen(engine, onNavigate = { screen = it })
        Screen.REMINDERS -> com.akashrajeev.voicebeam.reminders.ReminderScreen(onNavigate = { screen = it })
        Screen.RECALL -> com.akashrajeev.voicebeam.recall.RecallScreen(onNavigate = { screen = it })
        Screen.SETTINGS -> SettingsScreen(engine, onNavigate = { screen = it })
    }
}
