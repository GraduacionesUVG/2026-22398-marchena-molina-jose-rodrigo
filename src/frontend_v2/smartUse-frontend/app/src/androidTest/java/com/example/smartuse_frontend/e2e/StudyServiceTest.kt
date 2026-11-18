package com.example.smartuse_frontend.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.data.AppContainer
import com.example.smartuse_frontend.jobs.BackgroundJobScheduler
import com.example.smartuse_frontend.jobs.JobRuntime
import com.example.smartuse_frontend.jobs.JobSpecs
import com.example.smartuse_frontend.jobs.RealJobStateStore
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.studyNoticeContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class StudyServiceTest {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val backend = FakeBackend()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun launchWithServiceRunning(): ActivityScenario<MainActivity> {
        Seed.registeredSession(context, backend.token)
        DeviceShell.grantNotifications()
        DeviceShell.grantOverlay()
        DeviceShell.wakeAndUnlock()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        // Through the compose rule, not a plain poll: under the rule, frames (and so
        // the LaunchedEffect that runs LaunchCheck) only advance while the test syncs.
        try {
            compose.waitUntil(15_000) { StudyNotification.isForegroundService(context) }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the study service never reached the foreground; ${launchState()}", e)
        }
        return scenario
    }

    /** What the launch check decides from, for a failure message. */
    private fun launchState(): String = runBlocking {
        val container = AppContainer(context)
        "setupComplete=${container.setupStateRepository.isCompleteNow()} " +
            "ending=${RealJobStateStore(context).readEndMarks().state} " +
            "windowOpen=${container.sessionRepository.isCaptureWindowOpen()} " +
            "cycle=${WorkJobs.infos(context, JobSpecs.CYCLE_PERIODIC).map { it.state }} " +
            "notifications=${StudyNotification.text(context)}"
    }

    private fun unlockAgain() {
        DeviceShell.screenOff()
        Thread.sleep(1_000)
        DeviceShell.wakeAndUnlock()
    }

    @Test
    fun theEndingKeepsTheServiceRemindingAboutTheForms() {
        launchWithServiceRunning().use {
            runBlocking { JobRuntime.studyEnder(context, BackgroundJobScheduler(context)).end() }

            StudyNotification.awaitText(context, studyNoticeContent(StudyNotice.FormsPending).text)
            assertTrue("still in the foreground", StudyNotification.isForegroundService(context))
        }
    }

    @Test
    fun optingOutKeepsTheServiceRemindingAboutTheForms() {
        launchWithServiceRunning().use {
            runBlocking { AppContainer(context).optOutRepository.optOut() }

            StudyNotification.awaitText(context, studyNoticeContent(StudyNotice.FormsPending).text)
            assertTrue("still in the foreground", StudyNotification.isForegroundService(context))
        }
    }

    @Test
    fun anUnlockShowsTheReminderCardOnlyWhileTheFormsArePending() {
        launchWithServiceRunning().use {
            runBlocking { JobRuntime.studyEnder(context, BackgroundJobScheduler(context)).end() }
            StudyNotification.awaitText(context, studyNoticeContent(StudyNotice.FormsPending).text)
            DeviceShell.goHome()

            unlockAgain()
            await("the reminder card at unlock") { true.takeIf { OverlayOnScreen.isReminderCardUp() } }

            // Forms submitted (the final send fails, so the service stays in its sending mode).
            backend.respondAlways("POST /final", 500)
            runBlocking { RealJobStateStore(context).setFormsCompletedAt(Instant.now()) }
            StudyNotification.awaitText(context, studyNoticeContent(StudyNotice.Sending).text)
            await("the card gone with the mode") { true.takeIf { !OverlayOnScreen.isReminderCardUp() } }
            unlockAgain()
            Thread.sleep(2_000)
            assertFalse("no card while sending", OverlayOnScreen.isReminderCardUp())
        }
    }
}