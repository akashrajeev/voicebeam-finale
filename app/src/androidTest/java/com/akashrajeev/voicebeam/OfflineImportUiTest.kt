package com.akashrajeev.voicebeam

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.ui.OfflineVideoPanel
import org.junit.Rule
import org.junit.Test
import java.io.File

class OfflineImportUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun importedVideoLaneIsClearlyOffline() {
        compose.setContent { MaterialTheme { OfflineVideoPanel(true,onImported={}) } }
        compose.onNodeWithTag("pickOfflineVideo").assertIsEnabled()
        compose.onNodeWithText("Custom video - offline isolation").assertExists()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"offline-custom-video.png")
        java.io.FileOutputStream(file).use { stream ->
            compose.onRoot().captureToImage().let { image ->
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)
            }
        }
        android.util.Log.i("OfflineUi","screenshot="+file.absolutePath)
    }
    @Test fun cannotStartWhileLive() {
        compose.setContent { MaterialTheme { OfflineVideoPanel(false,onImported={}) } }
        compose.onNodeWithTag("pickOfflineVideo").assertIsNotEnabled()
        compose.onNodeWithText("Stop live listening and recording before offline isolation.").assertExists()
    }
}
