package com.akashrajeev.voicebeam
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.reminders.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
@RunWith(AndroidJUnit4::class) class ReminderFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun reviewAlertAndPersistence() {
        val app=compose.activity.application as VoiceBeamApp
        compose.runOnUiThread { compose.activity.setContent { ReminderScreen {} } }
        compose.waitForIdle();shot("reminders-home")
        val c=ReminderCard("test-card",System.currentTimeMillis(),"","Come back in two weeks and bring the report.",mapOf("what" to ReminderField("Come back","Come back"),"when" to ReminderField("in two weeks","in two weeks"),"bring" to ReminderField("report","bring the report")))
        app.reminders.store.save(c);app.reminders.refresh()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Review and confirm"));compose.onNodeWithText("Review and confirm").performClick();compose.waitUntil(5000) { compose.onAllNodesWithText("Did I understand?").fetchSemanticsNodes().isNotEmpty() };compose.onNode(hasScrollAction()).performScrollToNode(hasText("Did I understand?"));compose.waitForIdle();shot("reminders-confirm")
        assertEquals("review",ReminderStore(app).get(c.id)!!.status);assertNull(ReminderStore(app).get(c.id)!!.due)
        app.reminders.store.save(c.copy(status="ringing",due=System.currentTimeMillis()));app.reminders.refresh()
        compose.runOnUiThread { compose.activity.setContent { androidx.compose.material3.MaterialTheme { ReminderAlert(c.id) {} } } }
        compose.waitForIdle();shot("reminders-alert")
        compose.onNodeWithText("OK, I see it").assertExists()
        assertEquals("ringing",ReminderStore(app).get(c.id)!!.status)
        app.reminders.acknowledge(c.id)
        assertEquals("acknowledged",ReminderStore(app).get(c.id)!!.status)
        // Actual alarm permission gate and AlarmManager -> receiver -> foreground service path.
        val ui=InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("appops set ${app.packageName} SCHEDULE_EXACT_ALARM allow")).use { it.readBytes() }
        compose.waitUntil(5000) { ReminderScheduler(app).readiness()==null }
        val alarm=c.copy(id="test-alarm",status="confirmed",due=System.currentTimeMillis()+4000)
        app.reminders.store.save(alarm);ReminderScheduler(app).schedule(alarm)
        compose.waitUntil(15000) { ReminderStore(app).get(alarm.id)?.status=="ringing" }
        app.reminders.refresh()
        // Verify the actual dedicated Activity, not only service/state. FSI may become a heads-up notification.
        compose.runOnUiThread { compose.activity.startActivity(android.content.Intent(app,ReminderAlertActivity::class.java).putExtra("id",alarm.id)) }
        val device=InstrumentationRegistry.getInstrumentation().uiAutomation
        val limit=android.os.SystemClock.elapsedRealtime()+8000
        var found=false
        while(android.os.SystemClock.elapsedRealtime()<limit && !found) {
            found=device.rootInActiveWindow?.findAccessibilityNodeInfosByText("OK, I see it")?.isNotEmpty()==true
            if(!found) Thread.sleep(100)
        }
        assertTrue("Actual alert Activity must render its acknowledgement button",found)
        shot("reminders-scheduled-alert")
        compose.runOnUiThread { app.startService(android.content.Intent(app,ReminderAlertService::class.java).setAction(ReminderAlertService.SNOOZE).putExtra("id",alarm.id)) }
        compose.waitUntil(5000) { ReminderStore(app).get(alarm.id)?.status=="snoozed" }
        assertTrue(ReminderStore(app).get(alarm.id)!!.due!!>System.currentTimeMillis()+500000)
        app.reminders.cancel(alarm.id)
        val restored=alarm.copy(id="test-restore",status="confirmed",due=System.currentTimeMillis()+3600000)
        app.reminders.store.save(restored);ReminderScheduler(app).restore()
        assertEquals("confirmed",ReminderStore(app).get(restored.id)!!.status)
        app.reminders.cancel(restored.id)
    }
    private fun shot(name: String) { val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();File(compose.activity.getExternalFilesDir(null),"$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
}
