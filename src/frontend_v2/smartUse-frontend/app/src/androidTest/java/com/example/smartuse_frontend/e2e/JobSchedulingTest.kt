package com.example.smartuse_frontend.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import com.example.smartuse_frontend.jobs.BackgroundJobScheduler
import com.example.smartuse_frontend.jobs.JobLimits
import com.example.smartuse_frontend.jobs.JobSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.example.smartuse_frontend.jobs.JobName
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class JobSchedulingTest {

    @get:Rule val backend = FakeBackend()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun captureIsPlacedInTheOsSchedulerOnce() {
        Seed.registeredSession(context, backend.token)
        DeviceShell.grantUsageAccess()
        val scheduler = BackgroundJobScheduler(context)

        val scheduledAt = Instant.now()
        scheduler.ensureScheduled()
        // WorkManager runs a new periodic job at once; a second call while that run is
        // going stops it and retries it 30 s later. Let it finish first.
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, scheduledAt)
        WorkJobs.awaitQuiet(context)
        val idsBefore = WorkJobs.infos(context, JobSpecs.CYCLE_PERIODIC).map { it.id }.toSet()
        scheduler.ensureScheduled()

        val capture = WorkJobs.infos(context, JobSpecs.CYCLE_PERIODIC)
        assertEquals("the second call kept the same work", idsBefore, capture.map { it.id }.toSet())
        assertEquals("capture works", 1, capture.size)
        assertEquals(NetworkType.NOT_REQUIRED, capture.single().constraints.requiredNetworkType)
        // 6 h in release; debug builds may tune it in local.properties (JobLimits).
        val limits = JobLimits.current
        assertEquals(TimeUnit.MINUTES.toMillis(limits.effectiveCycleIntervalMinutes), capture.single().periodicityInfo?.repeatIntervalMillis)
        assertTrue(capture.single().state == WorkInfo.State.ENQUEUED || capture.single().state == WorkInfo.State.RUNNING)

        val captureJob = WorkJobs.scheduledJob(context, JobSpecs.CYCLE_PERIODIC)
        val inScheduler = DeviceShell.scheduledJobs()
        assertTrue("capture job $captureJob in JobScheduler $inScheduler", captureJob in inScheduler)

        WorkJobs.awaitQuiet(context) // the periodic jobs run once on their own
        BackendRecorder.assertContract(backend)
        DbInspector.assertConsistent(context)
    }
}
