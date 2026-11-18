package com.example.smartuse_frontend.e2e

import android.app.usage.UsageStatsManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.smartuse_frontend.jobs.BackgroundJobScheduler
import com.example.smartuse_frontend.jobs.JobName
import com.example.smartuse_frontend.jobs.JobSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BackgroundConditionsTest {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val backend = FakeBackend()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // WorkManager's in-process scheduler runs work regardless of Doze while this
    // process lives, so this asserts what is observable: the jobs stay in
    // JobScheduler while rare + idle, and the run records the bucket.
    @Test
    fun jobsStayScheduledInTheRareBucketAndDozeAndRunOnceAllowed() {
        Seed.collecting(context, backend)
        val usm = context.getSystemService(UsageStatsManager::class.java)
        // A battery-whitelisted app sits in the EXEMPTED bucket and ignores set-standby-bucket.
        DeviceShell.removeBatteryExemption()
        DeviceShell.setStandbyBucket("rare")
        await("standby bucket rare (now ${usm.appStandbyBucket})", 10_000) {
            true.takeIf { usm.appStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RARE }
        }
        val idle = DeviceShell.forceIdle()
        assumeFalse("this device can't enter deep idle: $idle", idle.contains("Unable", ignoreCase = true))

        assertNotNull(WorkJobs.scheduledJob(context, JobSpecs.CAPTURE_PERIODIC))
        assertNotNull(WorkJobs.scheduledJob(context, JobSpecs.SYNC_PERIODIC))

        DeviceShell.unforceIdle()
        val run = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, run)
        assertEquals(
            "the run logs the bucket it ran in",
            UsageStatsManager.STANDBY_BUCKET_RARE,
            WorkJobs.state(context, JobName.CAPTURE).lastStandbyBucket,
        )
        WorkJobs.awaitQuiet(context)
        BackendRecorder.assertContract(backend)
        DbInspector.assertConsistent(context, backend)
    }

    // A process kill mid-run (am kill) can't run here: the app
    // process is this test's process. A sync stopped while waiting for the
    // ack is the same interruption for the data (WorkerWrapper.interrupt, as the
    // OS's onStopJob does); true process death is manual test M1 (README).
    @Test
    fun aSyncStoppedWhileWaitingForTheAckLosesAndDuplicatesNothing() {
        Seed.collecting(context, backend)
        Usage.generate(context)
        backend.nextAckDelayMs = 10_000 // under the client's 15 s timeouts

        val captured = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, captured)
        await("the POST reached the backend") {
            backend.calls.firstOrNull { it.key == "POST /sync/canonical" && it.at > captured.toEpochMilli() }
        }
        WorkManager.getInstance(context).cancelUniqueWork(JobSpecs.SYNC_NOW).result.get()
        await("sync-now stopped") { true.takeIf { WorkJobs.infos(context, JobSpecs.SYNC_NOW).all { it.state == WorkInfo.State.CANCELLED } } }
        assertTrue("the stopped run did not count as a success", WorkJobs.lastSuccess(context, JobName.SYNC)!! < captured)

        val again = Instant.now()
        BackgroundJobScheduler(context).enqueueSyncNow()
        WorkJobs.awaitCompletedAfter(context, JobName.SYNC, again, timeoutMs = 60_000)
        WorkJobs.awaitQuiet(context)

        val acknowledged = backend.sentRecords(acknowledgedOnly = true).map { it.first }
        assertEquals("no epoch stored twice", acknowledged.size, acknowledged.toSet().size)
        BackendRecorder.assertContract(backend)
        DbInspector.assertConsistent(context, backend) // local cursor caught up with the server's
    }
}
