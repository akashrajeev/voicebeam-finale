package com.akashrajeev.voicebeam
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.ui.SettingsScreen
import org.junit.Rule
import org.junit.Test
import java.io.File
class ConversationUiTest {
 @get:Rule val compose=createComposeRule()
 @Test fun candidateVisible(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val engine=(context.applicationContext as VoiceBeamApp).engine
  compose.setContent{SettingsScreen(engine){}}
  compose.onNodeWithText("Conversation A/B candidate - 2s voice queries").assertIsDisplayed()
  compose.waitForIdle()
  val bmp=compose.onRoot().captureToImage().asAndroidBitmap()
  File(context.getExternalFilesDir(null),"conversation-candidate.png").outputStream().use{bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
 }
}
