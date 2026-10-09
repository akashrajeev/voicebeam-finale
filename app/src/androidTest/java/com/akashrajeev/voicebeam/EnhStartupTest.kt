package com.akashrajeev.voicebeam

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File

/** Startup/model-load check only. Not live acoustic quality or Bluetooth proof. */
@RunWith(AndroidJUnit4::class)
class EnhStartupTest {
    private val perms = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)
    @Test fun enhancementLoadsAndFocusOpens() {
        val engine = (compose.activity.application as VoiceBeamApp).engine
        compose.runOnUiThread { engine.updateSettings { it.copy(onboarded = true, debugFeed = true, denoise=.7f, quietOthers=.86f) }; engine.loadModels() }
        compose.waitUntil(120_000) { engine.state.value.modelsReady || engine.state.value.modelError != null }
        assertNull(engine.state.value.modelError)
        assertTrue(engine.state.value.modelsReady)
        if (compose.onAllNodesWithTag("start").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("start").performScrollTo().performClick()
        }
        compose.waitUntil(30_000) { engine.state.value.listening || engine.state.value.audioError != null }
        assertNull(engine.state.value.audioError)
        assertTrue(engine.state.value.listening)
        compose.onNodeWithText("ENH-7 | Enhanced listening").assertExists()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull(bmp)
        File(ctx.getExternalFilesDir(null), "ENH-7-focus.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i("ENH_CHECK", "ENH-7 native models loaded, focus opened, debug audio active. Acoustic quality and Bluetooth unverified.")
        compose.runOnUiThread { engine.stopListening() }
    }
}
