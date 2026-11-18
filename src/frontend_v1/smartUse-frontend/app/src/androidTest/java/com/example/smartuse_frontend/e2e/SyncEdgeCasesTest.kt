package com.example.smartuse_frontend.e2e

import android.net.ConnectivityManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.jobs.BackgroundJobScheduler
import com.example.smartuse_frontend.jobs.JobLimits
import com.example.smartuse_frontend.jobs.JobName
import com.example.smartuse_frontend.jobs.JobSpecs
import com.example.smartuse_frontend.network.FailureKind
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SyncEdgeCasesTest {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val backend = FakeBackend()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun acknowledgedIds() = backend.sentRecords(acknowledgedOnly = true).map { it.first }

    private fun syncNowAndWait(timeoutMs: Long = 60_000) {
        val before = Instant.now()
        BackgroundJobScheduler(context).enqueueSyncNow()
        WorkJobs.awaitAttemptAfter(context, JobName.SYNC, before, timeoutMs)
        WorkJobs.awaitQuiet(context)
    }

    private fun checks() {
        WorkJobs.awaitQuiet(context)
        BackendRecorder.assertContract(backend)
        DbInspector.assertConsistent(context, backend)
    }

    @Test
    fun aConflictSetsAsideExactlyTheNamedEpochAndTheRestArrives() {
        Seed.collecting(context, backend)
        Usage.generate(context)
        backend.conflictOnNextPost = true

        val captured = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, captured)
        WorkJobs.awaitCompletedAfter(context, JobName.SYNC, captured) // the same run resends the rest
        WorkJobs.awaitQuiet(context)

        val named = backend.conflicted.single()
        assertTrue("the named epoch is flagged sync_skipped", DbInspector.epochs(context).single { it.id == named }.skipped)
        assertTrue("the named epoch is never acknowledged", named !in acknowledgedIds())
        assertTrue("the rest of that batch arrived", acknowledgedIds().isNotEmpty())

        syncNowAndWait()
        assertTrue("the flag survives the next run", DbInspector.epochs(context).single { it.id == named }.skipped)
        assertEquals("sent once, in the 409 batch, never again", 1, backend.sentRecords().count { it.first == named })
        checks()
    }

    @Test
    fun a413LosesNothingAndTheNextRunResendsTheSameRecords() {
        Seed.collecting(context, backend)
        Usage.generate(context)
        backend.respond(
            "POST /sync/canonical",
            MockResponse().setResponseCode(413).setHeader("Content-Type", "application/json")
                .setBody(JSONObject().put("error", "payload_too_large").toString()),
        )

        val captured = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, captured)
        val failed = WorkJobs.awaitAttemptAfter(context, JobName.SYNC, captured)
        assertEquals("413 ends the run", FailureKind.PERMANENT, failed.lastFailure?.kind)
        val rejected = backend.calls.first { it.key == "POST /sync/canonical" && it.status == 413 }.recordIds()

        syncNowAndWait()
        assertTrue("every record of the rejected batch arrived later, same ids", acknowledgedIds().containsAll(rejected))
        checks()
    }

    @Test
    fun a5xxIsRetriedByWorkManagerAndNothingIsLost() {
        Seed.collecting(context, backend)
        Usage.generate(context)
        backend.respond(
            "POST /sync/canonical",
            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                .setBody(JSONObject().put("error", "unavailable").toString()),
        )

        val captured = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, captured)
        val failed = WorkJobs.awaitAttemptAfter(context, JobName.SYNC, captured)
        assertEquals("5xx is retried", FailureKind.TRANSIENT, failed.lastFailure?.kind)
        val rejected = backend.calls.first { it.key == "POST /sync/canonical" && it.status == 503 }.recordIds()

        // WorkManager's own retry: 30 s initial exponential backoff.
        WorkJobs.awaitCompletedAfter(context, JobName.SYNC, captured, timeoutMs = 120_000)
        assertTrue("the retry sent the same records", acknowledgedIds().containsAll(rejected))
        checks()
    }

    @Test
    fun a401MarksSyncAsFailingOnStatusAfterTheThreshold() {
        Seed.collecting(context, backend)
        val unauthorized = JSONObject().put("error", "unauthorized")
        backend.respondAlways("GET /sync/canonical/cursor", 401, unauthorized)
        backend.respondAlways("POST /sync/canonical", 401, unauthorized)

        val threshold = JobLimits.current.stuckAfterFailedRuns
        repeat(threshold) { syncNowAndWait() }
        val state = WorkJobs.state(context, JobName.SYNC)
        assertEquals(FailureKind.PERMANENT, state.lastFailure?.kind)
        assertTrue("${state.consecutiveFailedRuns} failed runs", state.consecutiveFailedRuns >= threshold)

        DeviceShell.wakeAndUnlock()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForText("Estado")
            compose.tapText("Estado")
            compose.waitForText("Con fallas")
            assertEquals("only sync is failing", 1, compose.count("Con fallas"))
            assertEquals(1, compose.count("Falla desde:", substring = true))
        }
        BackendRecorder.assertContract(backend)
    }

    @Test
    fun offlineCaptureKeepsGoingAndSyncCatchesUpWhenTheNetworkReturns() {
        Seed.collecting(context, backend)
        DeviceShell.setAirplaneMode(true)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        await("no network") { true.takeIf { connectivity.activeNetwork == null } }

        Usage.generate(context)
        val captured = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, captured) // capture needs no network
        await("sync-now waiting for the network") {
            WorkJobs.infos(context, JobSpecs.SYNC_NOW).singleOrNull { it.state == WorkInfo.State.ENQUEUED }
        }
        // Negative check: nothing may be posted while offline. A fixed hold is the only way to observe "didn't happen".
        Thread.sleep(10_000)
        assertTrue("no sync while offline", backend.calls.none { it.key == "POST /sync/canonical" && it.at > captured.toEpochMilli() })

        DeviceShell.setAirplaneMode(false)
        WorkJobs.awaitCompletedAfter(context, JobName.SYNC, captured, timeoutMs = 120_000)
        assertTrue("records arrived after reconnecting", backend.calls.any { it.key == "POST /sync/canonical" && it.acknowledged && it.at > captured.toEpochMilli() })
        checks()
    }
}
