package com.akashrajeev.voicebeam

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.engine.SaveMode
import com.akashrajeev.voicebeam.record.SessionMeta
import com.akashrajeev.voicebeam.ui.SessionsScreen
import org.junit.Rule
import org.junit.Test
import java.io.File

class OfflineSessionsUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun offlineLabelIsVisibleAndFitsExistingSessionUi() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val engine=(ctx.applicationContext as VoiceBeamApp).engine
        val (id,dir)=engine.sessions.newSessionDir()
        // UI-only fixture. The separate extractor test verifies real audio.
        File(dir,"extracted.wav").writeBytes(byteArrayOf(1))
        File(dir,"clean.m4a").writeBytes(byteArrayOf(1))
        engine.sessions.writeMeta(SessionMeta(id,"Offline extraction fixture",System.currentTimeMillis(),5864,SaveMode.AUDIO,"srt",dir),emptyList())
        engine.refreshSessions()
        compose.setContent { SessionsScreen(engine) {} }
        compose.onNodeWithText("Offline extraction fixture").performClick()
        compose.onNodeWithText("▶ Extracted (offline)").assertExists()
        compose.waitForIdle()
        val bmp=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        requireNotNull(bmp)
        File(ctx.getExternalFilesDir(null),"offline-sessions-ui.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
        engine.sessions.delete(engine.sessions.list().first{it.id==id})
    }
}
