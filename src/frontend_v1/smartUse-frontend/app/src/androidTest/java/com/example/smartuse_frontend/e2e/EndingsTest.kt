package com.example.smartuse_frontend.e2e

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.data.RealCredentialStore
import com.example.smartuse_frontend.jobs.JobName
import com.example.smartuse_frontend.jobs.JobSpecs
import com.example.smartuse_frontend.notification.HardOptOutStage
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.studyNoticeContent
import com.example.smartuse_frontend.study.END_OF_COLLECTION_TEXT
import com.example.smartuse_frontend.ui.optout.RETRYING_LINE
import com.example.smartuse_frontend.ui.optout.UNINSTALL_HINT
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class EndingsTest {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val backend = FakeBackend()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun awaitErased() = await("local data erased") { true.takeIf { !DbInspector.databasesExist(context) } }

    private fun awaitCaptureAndSyncCancelled() {
        for (name in listOf(JobSpecs.CAPTURE_PERIODIC, JobSpecs.SYNC_PERIODIC)) {
            await("$name cancelled") { true.takeIf { WorkJobs.infos(context, name).all { it.state == WorkInfo.State.CANCELLED } } }
        }
    }

    @Test
    fun day21EndsCollectionOnLaunchWithoutAnyRequest() {
        Seed.registeredSession(context, backend.token, signupHoursAgo = 21L * 24 + 1)
        Seed.canonicalAndRawDatabases(context)
        DeviceShell.grantNotifications()
        DeviceShell.wakeAndUnlock()

        val launchedAt = System.currentTimeMillis()
        ActivityScenario.launch(MainActivity::class.java).use { compose.waitForText(END_OF_COLLECTION_TEXT) }

        awaitErased()
        assertNull("credentials erased", runBlocking { RealCredentialStore(context).authToken() })
        StudyNotification.awaitText(context, studyNoticeContent(StudyNotice.EndOfCollection).text)
        // Day 21 is decided from the stored signup alone: no cutoff fetch, no sync.
        assertEquals(emptyList<RecordedCall>(), BackendRecorder.callsAfter(backend, launchedAt))
    }

    @Test
    fun aSoftOptOutDrainsPendingRecordsThenAcknowledgesAndErases() {
        Seed.collecting(context, backend)
        // Records stay pending: sync fails permanently (no automatic retry) until the drain.
        backend.respondAlways("POST /sync/canonical", 401)
        Usage.generate(context)
        val captured = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, captured)
        WorkJobs.awaitAttemptAfter(context, JobName.SYNC, captured)
        WorkJobs.awaitQuiet(context)
        backend.stopRespondingAlways("POST /sync/canonical")
        val acknowledgedBefore = backend.sentRecords(acknowledgedOnly = true).map { it.first }.toSet()
        val pending = DbInspector.epochs(context).dropLast(1).filter { !it.skipped }.map { it.id } - acknowledgedBefore
        assertTrue("something to drain", pending.isNotEmpty())

        DeviceShell.wakeAndUnlock()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForText("Retirarse del estudio")
            compose.tapText("Retirarse del estudio")
            compose.waitForText("Retiro parcial")
            compose.tapText("Retiro parcial")
            compose.waitForText("Confirmar retiro parcial")
            compose.tapText("Confirmar")
            compose.waitForText("Retiro completado. Gracias por tu participación. Ya puedes desinstalar la aplicación.")
        }

        val ack = await("POST /opt-out/soft", 60_000) { backend.calls.firstOrNull { it.key == "POST /opt-out/soft" } }
        awaitErased()
        val acknowledged = backend.sentRecords(acknowledgedOnly = true).map { it.first }
        assertTrue("every pending record was drained", acknowledged.containsAll(pending))
        assertTrue("the drain came before the ack", backend.calls.filter { it.path.startsWith("/sync") }.all { it.at <= ack.at })
        assertEquals("one soft opt-out", 1, backend.calls.count { it.key == "POST /opt-out/soft" })
        awaitCaptureAndSyncCancelled()
        BackendRecorder.assertContract(backend)
    }

    @Test
    fun aFailingHardOptOutKeepsRetryingInTheBackgroundAndKeepsUninstallWaiting() {
        Seed.registeredSession(context, backend.token)
        Seed.canonicalAndRawDatabases(context)
        DeviceShell.grantNotifications()
        DeviceShell.wakeAndUnlock()
        backend.respondAlways("POST /opt-out/hard", 503)

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForText("Retirarse del estudio")
            compose.tapText("Retirarse del estudio")
            compose.waitForText("Retiro total")
            compose.tapText("Retiro total")
            compose.waitForText("Confirmar retiro total")
            compose.tapText("Confirmar")
            compose.waitForText(RETRYING_LINE, 30_000)
            compose.onNode(hasText("Desinstalar ahora") and hasClickAction()).assertIsNotEnabled()
            compose.waitForText(UNINSTALL_HINT)
        }

        // The finish worker (queued before the inline call) failed too and backs off linearly (10 min).
        val finish = await("opt-out-finish retrying with backoff", 60_000) {
            WorkJobs.infos(context, "opt-out-finish").singleOrNull()
                ?.takeIf { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount >= 1 }
        }
        val wait = finish.nextScheduleTimeMillis - System.currentTimeMillis()
        assertTrue("next retry in ~10 min, got ${wait / 1000} s", wait > 8 * 60_000)
        StudyNotification.awaitText(context, studyNoticeContent(StudyNotice.HardOptOut(HardOptOutStage.FAILED)).text)
        assertTrue("inline call and the worker's call", backend.calls.count { it.key == "POST /opt-out/hard" } >= 2)
        assertEquals("the token stays for the retries", backend.token, runBlocking { RealCredentialStore(context).authToken() })
        val firstOptOut = backend.calls.first { it.key == "POST /opt-out/hard" }.at
        assertTrue("no sync after the opt-out", BackendRecorder.callsAfter(backend, firstOptOut).none { it.path.startsWith("/sync") })
        BackendRecorder.assertContract(backend)
    }

    @Test
    fun aHardOptOutConfirmedAfterTheCutoffEndsCollectionInsteadOfSending() {
        Seed.registeredSession(context, backend.token)
        Seed.canonicalAndRawDatabases(context)
        // No usage access: the launch's capture fails at once and enqueues no sync-now.
        DeviceShell.grantNotifications()
        DeviceShell.wakeAndUnlock()

        val launchedAt = Instant.now()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForText("Retirarse del estudio")
            // The launch's own job runs finish while no cutoff is published; any run
            // after this point would end collection itself and hide the confirm check.
            WorkJobs.awaitAttemptAfter(context, JobName.CAPTURE, launchedAt)
            WorkJobs.awaitAttemptAfter(context, JobName.SYNC, launchedAt)
            WorkJobs.awaitQuiet(context)
            backend.cutoff = LocalDate.now().minusDays(1).toString()

            compose.tapText("Retirarse del estudio")
            compose.waitForText("Retiro total")
            compose.tapText("Retiro total")
            compose.waitForText("Confirmar retiro total")
            compose.tapText("Confirmar")
            compose.waitForText(END_OF_COLLECTION_TEXT, 30_000)
        }

        awaitErased()
        assertTrue("nothing sent: the ending ran instead", backend.calls.none { it.key == "POST /opt-out/hard" })
        StudyNotification.awaitText(context, studyNoticeContent(StudyNotice.EndOfCollection).text)
        awaitCaptureAndSyncCancelled()
        BackendRecorder.assertContract(backend)
    }
}
