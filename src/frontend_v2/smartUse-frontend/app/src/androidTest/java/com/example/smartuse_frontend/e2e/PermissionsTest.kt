package com.example.smartuse_frontend.e2e

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.data.KeyPermission
import com.example.smartuse_frontend.jobs.BackgroundJobScheduler
import com.example.smartuse_frontend.jobs.JobName
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.studyNoticeContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PermissionsTest {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val backend = FakeBackend()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun recording(missing: Set<KeyPermission>) = studyNoticeContent(StudyNotice.Recording(missing)).text

    /** Welcome → Estado; the "Permisos necesarios" card appears only when something is missing. */
    private fun openStatusExpectingMissing() {
        compose.waitForText("Estado")
        compose.tapText("Estado")
        compose.waitForText("Permisos necesarios")
    }

    @Test
    fun aUsageAccessLapseShowsEverywhereAndTheNextCaptureBackfillsTheGap() {
        Seed.collecting(context, backend)

        DeviceShell.revokeUsageAccess()
        val revokedAt = System.currentTimeMillis()
        Usage.generate(context, verify = false) // the OS records these; the app can't read them now
        val failedRun = WorkJobs.forceCapture(context)
        val failed = WorkJobs.awaitAttemptAfter(context, JobName.CAPTURE, failedRun)
        assertNotNull("capture failed while usage access was revoked", failed.lastFailure)
        StudyNotification.awaitText(context, recording(setOf(KeyPermission.USAGE_ACCESS)))
        ActivityScenario.launch(MainActivity::class.java).use {
            openStatusExpectingMissing()
            assertEquals("one permission missing on Status", 1, compose.count("Falta"))
        }

        DeviceShell.grantUsageAccess()
        Usage.awaitScreenEvents(context, revokedAt)
        val restored = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, restored)
        val gap = DbInspector.epochs(context).filter { it.startTs in revokedAt..restored.toEpochMilli() }
        assertTrue("the gap was backfilled from the unmoved cursor, got ${gap.size} epochs", gap.size >= 2)
        StudyNotification.awaitText(context, recording(emptySet()))

        WorkJobs.awaitQuiet(context)
        BackendRecorder.assertContract(backend)
        DbInspector.assertConsistent(context)
    }

    // POST_NOTIFICATIONS is never revoked here: `pm revoke` kills the app
    // process, which is this test's process. The install starts without it
    // (the runner passes --grant-permissions none). Revoking it mid-study is
    // manual test M2 (README).
    @Test
    fun missingNotificationsShowOnStatusAndGrantingThemBringsTheNoticeBack() {
        // Skipped, not failed, when the install pre-granted it (Test Lab without
        // gcloud's beta component can't pass --grant-permissions none).
        assumeFalse(
            "POST_NOTIFICATIONS was pre-granted at install; the runner needs `gcloud components install beta`",
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
        Seed.registeredSession(context, backend.token)
        DeviceShell.grantUsageAccess()
        DeviceShell.exemptFromBatteryOptimization()
        DeviceShell.grantOverlay()
        val scheduledAt = Instant.now()
        BackgroundJobScheduler(context).ensureScheduled()
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, scheduledAt)
        WorkJobs.awaitQuiet(context)
        assertNull("nothing can be shown without the permission", StudyNotification.text(context))

        DeviceShell.wakeAndUnlock()
        ActivityScenario.launch(MainActivity::class.java).use {
            openStatusExpectingMissing()
            assertEquals("one permission missing on Status", 1, compose.count("Falta"))
        }

        DeviceShell.grantNotifications()
        val run = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, run)
        StudyNotification.awaitText(context, recording(emptySet()))

        WorkJobs.awaitQuiet(context)
        BackendRecorder.assertContract(backend)
        DbInspector.assertConsistent(context)
    }

    @Test
    fun aLostBatteryExemptionShowsInTheNoticeAndClearsWhenRestored() {
        Seed.collecting(context, backend)

        DeviceShell.removeBatteryExemption()
        val lapsed = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, lapsed) // capture itself still works
        StudyNotification.awaitText(context, recording(setOf(KeyPermission.BATTERY_EXEMPTION)))

        DeviceShell.exemptFromBatteryOptimization()
        val restored = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, restored)
        StudyNotification.awaitText(context, recording(emptySet()))

        WorkJobs.awaitQuiet(context)
        BackendRecorder.assertContract(backend)
        DbInspector.assertConsistent(context)
    }

    @Test
    fun aLostOverlayPermissionShowsInTheNoticeAndOnStatusAndClearsWhenRestored() {
        Seed.collecting(context, backend)

        DeviceShell.revokeOverlay()
        val lapsed = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, lapsed) // capture itself still works
        StudyNotification.awaitText(context, recording(setOf(KeyPermission.OVERLAY)))
        DeviceShell.wakeAndUnlock()
        ActivityScenario.launch(MainActivity::class.java).use {
            openStatusExpectingMissing()
            assertEquals("one permission missing on Status", 1, compose.count("Falta"))
        }

        DeviceShell.grantOverlay()
        val restored = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, restored)
        StudyNotification.awaitText(context, recording(emptySet()))

        WorkJobs.awaitQuiet(context)
        BackendRecorder.assertContract(backend)
        DbInspector.assertConsistent(context)
    }
}
