package com.akashrajeev.voicebeam

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.ui.BottomNav
import com.akashrajeev.voicebeam.ui.SessionsScreen
import com.akashrajeev.voicebeam.ui.VoiceBeamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class UnifiedNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun videoTabRoutesToSessions() {
        var destination: Screen? = null
        compose.setContent { VoiceBeamTheme { BottomNav(Screen.RECALL) { destination=it } } }
        compose.onNodeWithText("Listen").assertExists()
        compose.onNodeWithText("Recall").assertExists()
        compose.onNodeWithText("Settings").assertExists()
        compose.onNodeWithText("Video").performClick()
        compose.runOnIdle { assertEquals(Screen.SESSIONS,destination) }
    }
    @Test fun videoScreenShowsImportAndVisibleTab() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as VoiceBeamApp
        compose.setContent { VoiceBeamTheme { SessionsScreen(app.engine,onNavigate={}) } }
        compose.onNodeWithText("Video & sessions").assertExists()
        compose.onNodeWithText("Custom video - offline isolation").assertExists()
        compose.onNodeWithText("1  Choose your video").assertExists()
        compose.onNodeWithText("2  Mark 3-10 seconds of the target alone").assertExists()
        compose.onNodeWithText("3  Isolate locally, then play both versions").assertExists()
        compose.onNodeWithTag("pickOfflineVideo").assertIsEnabled()
        compose.onNodeWithText("Video").assertExists()
        val file=File(context.getExternalFilesDir(null),"unified-video-navigation.png")
        file.outputStream().use { stream -> compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream) }
    }
}
