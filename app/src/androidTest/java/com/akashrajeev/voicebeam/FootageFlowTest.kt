package com.akashrajeev.voicebeam
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.footage.FootageScreen
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
@RunWith(AndroidJUnit4::class)
class FootageFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun importAnalyzeAndRender() {
        val app=compose.activity.application as VoiceBeamApp
        compose.runOnUiThread { compose.activity.setContentForFootage() }
        val test=InstrumentationRegistry.getInstrumentation().context
        val fixture=File(app.cacheDir,"fixture.mp4")
        test.assets.open("footage-synthetic.mp4").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        app.footage.import(Uri.fromFile(fixture))
        compose.waitUntil(180000) { !app.footage.state.value.busy && app.footage.state.value.clips.isNotEmpty() }
        val clip=app.footage.state.value.clips.first()
        assertTrue(clip.duration>59000);assertEquals("ready",clip.status);assertTrue(clip.windows.isNotEmpty())
        compose.waitForIdle();shot("footage-home")
        compose.onNodeWithText(clip.title).performScrollTo().performClick()
        compose.waitForIdle();shot("footage-voices")
        compose.onNodeWithText("Review " + clip.groups().find { it.id==clip.selected }!!.name).performScrollTo().performClick()
        compose.waitForIdle();shot("footage-compare")
        compose.onNodeWithText("Transcribe matched moments with Gemma").assertExists()
        val reconstructed=com.akashrajeev.voicebeam.footage.FootageStore(app).clips().first { it.id==clip.id }
        assertEquals(clip.windows,reconstructed.windows);assertEquals(clip.selected,reconstructed.selected)
    }
    private fun shot(name: String) {
        val i=InstrumentationRegistry.getInstrumentation();val b=i.uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null),"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
private fun MainActivity.setContentForFootage() { setContent { com.akashrajeev.voicebeam.ui.VoiceBeamTheme { FootageScreen {} } } }
