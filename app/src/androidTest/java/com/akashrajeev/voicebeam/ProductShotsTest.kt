package com.akashrajeev.voicebeam

import android.Manifest
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.compose.ui.test.assertIsEnabled
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
import java.io.ByteArrayOutputStream

/** Captures product screenshots (caption mode, save sheet, sessions) for review. */
@RunWith(AndroidJUnit4::class)
class ProductShotsTest {
    private val perms = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val engine get() = (compose.activity.application as VoiceBeamApp).engine

    @Test fun captureProductScreens() {
        compose.runOnUiThread { engine.updateSettings { it.copy(onboarded = true) } }
        compose.waitUntil(90_000) { engine.state.value.modelsReady }
        if (compose.onAllNodesWithTag("start").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("start").performScrollTo().assertIsEnabled().performClick()
        }
        compose.waitUntil(30_000) { engine.state.value.listening }

        // A short recording so the Sessions screen has a real row.
        val before = engine.sessionList.value.size
        compose.onNodeWithTag("record").performClick()
        compose.waitUntil(5_000) { engine.state.value.recording.active }
        Thread.sleep(3000)
        compose.onNodeWithTag("record").performClick()
        compose.waitUntil(30_000) { !engine.state.value.recording.exporting && engine.sessionList.value.size > before }

        // Big-text caption mode.
        compose.onNodeWithTag("captionMode").performClick()
        compose.onNodeWithTag("captionScreen").assertExists()
        Thread.sleep(1000)
        shot("p1-caption-mode")
        compose.onNodeWithText("Back to camera").performClick()
        compose.onNodeWithTag("record").assertExists()

        // Sessions list.
        compose.onNodeWithText("Sessions").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("sessionList").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1000)
        shot("p2-sessions")

        // Save-mode sheet last: nothing to dismiss afterwards, so no fragile
        // back press (a back press with no sheet open finishes the activity).
        compose.onNodeWithText("Focus").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("saveMode").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { !engine.state.value.recording.active && !engine.state.value.recording.exporting }
        Thread.sleep(1500)
        // The camera preview can keep Compose "busy" for a while, which makes the
        // idle sync inside performClick time out. Treat that as retryable.
        fun sheetOpen() = try {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Audio only")).fetchSemanticsNodes().isNotEmpty()
        } catch (t: Throwable) { false }
        var opened = false
        for (attempt in 1..4) {
            try {
                compose.onNodeWithTag("saveMode").performClick()
                compose.waitUntil(5_000) { sheetOpen() }
                opened = true
                break
            } catch (t: Throwable) {
                android.util.Log.w("VBSHT", "save sheet attempt $attempt: " + t.javaClass.simpleName)
                if (sheetOpen()) { opened = true; break }
                Thread.sleep(2000)
            }
        }
        if (!opened) android.util.Log.w("VBSHT", "save sheet never opened")
        Thread.sleep(1200)
        shot("p3-save-sheet")
    }

    private fun shot(name: String) {
        // The compose test clock only recomposes when the test syncs, so a raw
        // screenshot can show a stale frame (e.g. status chip lagging the lock
        // ring). Push a few frames through first.
        try { compose.mainClock.advanceTimeBy(600) } catch (t: Throwable) { Log.w("VBSHT", "clock advance failed: ${t.message}") }
        Thread.sleep(400)
        // takeScreenshot() intermittently returns null under load; retry a few times.
        var bmp: Bitmap? = null
        for (attempt in 1..4) {
            try {
                bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            } catch (t: Throwable) {
                Log.w("VBSHT", "shot $name attempt $attempt failed: ${t.message}")
            }
            if (bmp != null) break
            Thread.sleep(600)
        }
        if (bmp == null) {
            Log.w("VBSHT", "shot $name gave up: takeScreenshot returned null")
            return
        }
        try {
            val buf = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 90, buf)
            val b64 = Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)
            Log.i("VBSHT", "BEGIN $name")
            var i = 0
            while (i < b64.length) {
                Log.i("VBSHT", "D " + b64.substring(i, minOf(i + 3000, b64.length)))
                i += 3000
            }
            Log.i("VBSHT", "END $name")
        } catch (t: Throwable) {
            Log.w("VBSHT", "shot failed: ${t.message}")
        }
    }
}
