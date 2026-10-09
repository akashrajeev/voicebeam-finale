package com.akashrajeev.voicebeam
import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import java.io.File
class EmulatorSmokeTest {
 private val compose=createAndroidComposeRule<MainActivity>()
 private val perms=GrantPermissionRule.grant(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO)
 @get:Rule val chain:RuleChain=RuleChain.outerRule(perms).around(compose)
 @Test fun nativeCameraMicStartup() {
  val e=(compose.activity.application as VoiceBeamApp).engine
  compose.runOnUiThread { e.updateSettings{it.copy(onboarded=true,debugFeed=false)};e.loadModels() }
  compose.waitUntil(120000){e.state.value.modelsReady||e.state.value.modelError!=null}
  assertNull(e.state.value.modelError);assertTrue(e.state.value.modelsReady)
  if(compose.onAllNodesWithTag("start").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithTag("start").performScrollTo().performClick()
  compose.waitUntil(30000){e.state.value.listening||e.state.value.audioError!=null}
  Thread.sleep(20000)
  assertNull(e.state.value.audioError);assertTrue(e.state.value.listening)
  val ins=InstrumentationRegistry.getInstrumentation();val bmp=ins.uiAutomation.takeScreenshot()
  File(ins.targetContext.getExternalFilesDir(null),"emulator-smoke.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
  compose.runOnUiThread{e.stopListening()}
 }
}
