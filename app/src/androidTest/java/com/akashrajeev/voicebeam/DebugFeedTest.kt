package com.akashrajeev.voicebeam

import android.Manifest
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import androidx.test.rule.GrantPermissionRule
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Exercises the demo feed: a recorded two-person clip drives the real face,
 * lip-gate, voice and caption pipelines. Takes screenshots through logcat
 * (VBSHT chunks) so CI can reassemble them.
 */
@RunWith(AndroidJUnit4::class)
class DebugFeedTest {
    private val perms = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val engine get() = (compose.activity.application as VoiceBeamApp).engine

    @Test fun demoFeedDrivesFacesLockAndCaptions() {
        compose.runOnUiThread { engine.updateSettings { it.copy(debugFeed = true, onboarded = true) } }
        compose.waitUntil(90_000) { engine.state.value.modelsReady }
        if (compose.onAllNodes(androidx.compose.ui.test.hasTestTag("start")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("start").performScrollTo().assertIsEnabled().performClick()
        }
        compose.waitUntil(30_000) { engine.state.value.listening }

        // Give the demo branch a moment, then record what the screen looks like.
        Thread.sleep(8000)
        shot("0-screen")

        // Two faces from the recorded clip (ML Kit on the emulator, MediaPipe on a phone).
        compose.waitUntil(90_000) { engine.state.value.faces.size >= 2 }
        shot("1-faces")

        // Tap the leftmost detected face (Armstrong) at its actual centre.
        val target = compose.runOnUiThread {
            val st = engine.state.value
            st.faces.minByOrNull { it.box.cx }?.let { f -> Triple(f.box.cx, f.box.cy, st.imageWidth to st.imageHeight) }
        } ?: error("no face to tap")
        val (tnx, tny, dims) = target
        val (iw, ih) = dims
        compose.onNodeWithTag("faces").performTouchInput {
            val fitScale = minOf(width / iw.toFloat(), height / ih.toFloat())
            val fitOx = (width - iw * fitScale) / 2f
            val fitOy = (height - ih * fitScale) / 2f
            down(androidx.compose.ui.geometry.Offset(fitOx + tnx * iw * fitScale, fitOy + tny * ih * fitScale))
            up()
        }
        compose.waitUntil(15_000) { engine.state.value.lockedId != null }
        android.util.Log.i("VoiceBeamTest", "locked id=" + engine.state.value.lockedId)
        // Let the UI catch up with the engine so the shot shows the lock ring and chip.
        // (No compose idling/tree queries here: the live demo feed redraws off the
        // main thread and Espresso idling trips Compose's thread check.)
        Thread.sleep(2500)
        shot("2-locked")

        // The clip alternates talkers every 15 s; the locked person must light up.
        var polls = 0
        compose.waitUntil(75_000) {
            val st = engine.state.value
            if (++polls % 40 == 0) android.util.Log.i("VoiceBeamTest", "lockedSpeaking=" + st.lockedSpeaking + " locked=" + st.lockedId + " faces=" + st.faces.size)
            st.lockedSpeaking > 0.35f
        }
        shot("3-speaking")

        // Captions from the clip's speech. Ground truth from an offline run of the
        // same ASR model: Armstrong says "...approach ... safety into the spacecraft...",
        // Aldrin says "the Russians are to be congratulated...".
        compose.waitUntil(180_000) {
            engine.state.value.segments.any {
                val u = it.text.uppercase()
                u.contains("APPROACH") || u.contains("SAFETY") || u.contains("RUSSIAN") || u.contains("CONGRATULATED")
            }
        }
        shot("4-captions")
        assertTrue(engine.state.value.segments.isNotEmpty())

        // Keep listening until the other talker (Aldrin, 15-30 s of each minute) has
        // produced captions, so the score covers rejection as well as acceptance.
        fun isArmstrong(ms: Long): Boolean { val m = ms % 60_000; return m in 0..14_999 || m in 30_000..44_999 }
        try {
            compose.waitUntil(150_000) {
                engine.state.value.segments.any { it.text.isNotBlank() && !isArmstrong((it.startMs + it.endMs) / 2) } &&
                    engine.state.value.segments.any { it.text.isNotBlank() && it.startMs >= 30_000 }
            }
        } catch (t: Throwable) {
            android.util.Log.w("VoiceBeamTest", "other-speaker segments did not arrive in time: " + t.message)
        }

        // Voice-lock accuracy against the clip's known talker schedule.
        val segs = engine.state.value.segments.filter { it.text.isNotBlank() }
        var ok = 0; var tgt = 0; var tgtOk = 0; var oth = 0; var othOk = 0
        for (seg in segs) {
            val armstrong = isArmstrong((seg.startMs + seg.endMs) / 2)
            if (armstrong) { tgt++; if (seg.isTarget) tgtOk++ } else { oth++; if (!seg.isTarget) othOk++ }
            if (armstrong == seg.isTarget) ok++
        }
        android.util.Log.i("VoiceBeamTest", "voicelock locked-speaker kept=" + tgtOk + "/" + tgt + " other-speaker rejected=" + othOk + "/" + oth)
        android.util.Log.i("VoiceBeamTest", "voicelock acc=" + ok + "/" + segs.size + " (" + (if (segs.isEmpty()) 0 else ok * 100 / segs.size) + "%)")
        for (seg in segs) android.util.Log.i("VoiceBeamTest", "seg [" + seg.startMs + "-" + seg.endMs + "] truth=" + (if (isArmstrong((seg.startMs + seg.endMs) / 2)) "locked" else "other") + " target=" + seg.isTarget + " '" + seg.text.take(60) + "'")
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
