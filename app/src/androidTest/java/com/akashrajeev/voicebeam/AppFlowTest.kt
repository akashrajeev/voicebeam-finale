package com.akashrajeev.voicebeam

import android.Manifest
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.akashrajeev.voicebeam.engine.SaveMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Drives the real app: setup, live focus screen, caption mode, recording, sessions. */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    private val perms = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val engine get() = (compose.activity.application as VoiceBeamApp).engine

    private fun reachFocus() {
        compose.waitUntil(60_000) { engine.state.value.modelsReady }
        if (compose.onAllNodesWithTagExists("start")) {
            compose.onNodeWithTag("start").performScrollTo().assertIsEnabled().performClick()
        }
        compose.waitUntil(20_000) { engine.state.value.listening }
    }

    @Test fun setupToFocusToCaptionMode() {
        reachFocus()
        compose.onNodeWithTag("caption").assertExists()
        compose.onNodeWithTag("captionMode").performClick()
        compose.onNodeWithTag("captionScreen").assertExists()
        compose.onNodeWithText("Back to camera").performClick()
        compose.onNodeWithTag("record").assertExists()
    }

    @Test fun recordAudioSessionEndToEnd() {
        reachFocus()
        val before = engine.sessionList.value.size
        compose.runOnUiThread { engine.updateSettings { it.copy(saveMode = SaveMode.AUDIO, keepRawAudio = true) } }
        compose.onNodeWithTag("record").performClick()
        compose.waitUntil(5_000) { engine.state.value.recording.active }
        compose.onNodeWithTag("rec").assertExists()
        Thread.sleep(3000)
        compose.onNodeWithTag("record").performClick()
        compose.waitUntil(30_000) { !engine.state.value.recording.exporting && engine.sessionList.value.size > before }
        val s = engine.sessionList.value.first()
        assertTrue("clean audio saved", s.cleanAudio.length() > 1000)
        assertTrue("raw copy saved", s.rawWav.length() > 16000 * 2 * 2)
        assertTrue("srt written", s.srt.exists())
        compose.onNodeWithText("Sessions").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("sessionList").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sessionList").assertExists()
        compose.onNodeWithText(s.title).assertExists()
    }

    @Test fun stageServerServesCaptions() {
        reachFocus()
        compose.runOnUiThread { engine.updateSettings { it.copy(stageEnabled = true) } }
        compose.waitUntil(5_000) { engine.state.value.stageUrl != null }
        val body = java.net.URL("http://127.0.0.1:8765/state.json").readText()
        assertTrue(body.contains("\"lines\""))
        val page = java.net.URL("http://127.0.0.1:8765/").readText()
        assertTrue(page.contains("VoiceBeam"))
        compose.runOnUiThread { engine.updateSettings { it.copy(stageEnabled = false) } }
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
