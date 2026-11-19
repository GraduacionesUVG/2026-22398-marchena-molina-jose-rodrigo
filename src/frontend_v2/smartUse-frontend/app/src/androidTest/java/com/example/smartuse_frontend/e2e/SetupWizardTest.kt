package com.example.smartuse_frontend.e2e

import android.app.NotificationManager
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.jobs.JobSpecs
import org.json.JSONObject
import com.example.smartuse_frontend.data.RealSetupStateRepository
import com.example.smartuse_frontend.ui.setup.EARLIER_USAGE_ACCEPT
import com.example.smartuse_frontend.ui.setup.historyStart
import com.example.smartuse_frontend.ui.setup.usmHasEarlierUsage
import com.example.smartuse_frontend.ui.setup.windowEnd
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class SetupWizardTest {

    @get:Rule(order = 0) val backend = FakeBackend()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aParticipantCanFinishSetupAndTheJobsGetScheduled() {
        DeviceShell.wakeAndUnlock()
        DeviceShell.grantUsageAccess()
        DeviceShell.grantNotifications()
        DeviceShell.exemptFromBatteryOptimization()
        DeviceShell.grantOverlay()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.walkSetupWizard(score = 37) {
                val score = it.onNode(hasSetTextAction() and hasText("Puntaje SAS-SV"))
                score.performTextInput("9")
                it.waitForText("Debe estar entre 10 y 60")
                score.performTextClearance()
            }
        }

        val register = backend.calls.single { it.key == "POST /users" }
        val body = JSONObject(register.body)
        assertEquals(21, body.getInt("age_at_signup"))
        assertEquals("Femenino", body.getString("gender"))
        assertEquals(Seed.QA_MAJOR, body.getString("university_major"))
        assertEquals("inference", body.getString("experiment_group"))
        assertEquals(37, body.getInt("test_score"))

        await("jobs scheduled after setup") {
            WorkJobs.infos(context, JobSpecs.CYCLE_PERIODIC).takeIf { it.isNotEmpty() }
        }
        await("the study service in the foreground after setup") {
            true.takeIf { StudyNotification.isForegroundService(context) }
        }
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue("the recording notification is posted", notifications.any { it.id == 3001 })
        WorkJobs.awaitQuiet(context)
        BackendRecorder.assertContract(backend)
        DbInspector.assertConsistent(context)
    }

    /**
     * The consent step shows exactly when the phone has usage in the 3 days
     * before today (a fresh Test Lab device usually has none: the skip path);
     * accepting stores the window and capture starts there.
     */
    @Test
    fun theEarlierUsageStepShowsOnlyWithHistory() {
        DeviceShell.wakeAndUnlock()
        DeviceShell.grantUsageAccess()
        DeviceShell.grantNotifications()
        DeviceShell.exemptFromBatteryOptimization()
        DeviceShell.grantOverlay()
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val expected = usmHasEarlierUsage(context, historyStart(now, zone), windowEnd(now, zone))

        val shown = ActivityScenario.launch(MainActivity::class.java).use {
            compose.walkSetupWizard(score = 37, earlierUsage = EARLIER_USAGE_ACCEPT)
        }

        assertEquals("the step shows iff there is earlier usage", expected, shown)
        val stored = runBlocking { RealSetupStateRepository(context).historyStart() }
        assertEquals(if (shown) historyStart(now, zone) else null, stored)
        assertFalse("the choice is never sent", backend.calls.single { it.key == "POST /users" }.body.contains("history"))
        WorkJobs.awaitQuiet(context)
        DbInspector.assertConsistent(context)
    }
}
