package com.example.smartuse_frontend.jobs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

class AlwaysFailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.failure()
}

@RunWith(AndroidJUnit4::class)
class BackgroundJobSchedulerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun scheduler(capture: Int = 360, sync: Int = 360) =
        BackgroundJobScheduler(context, JobLimits.fromConfig(capture, sync, 500, 3)) {}

    @Test
    fun ensureScheduledTwiceYieldsOneOfEachPeriodicWork() {
        val scheduler = scheduler()

        scheduler.ensureScheduled()
        scheduler.ensureScheduled()

        assertEquals(1, infos(JobSpecs.CAPTURE_PERIODIC).size)
        assertEquals(1, infos(JobSpecs.SYNC_PERIODIC).size)
    }

    @Test
    fun syncWorksRequireANetworkAndCaptureDoesNot() {
        scheduler().ensureScheduled()

        assertEquals(NetworkType.CONNECTED, infos(JobSpecs.SYNC_PERIODIC).single().constraints.requiredNetworkType)
        assertEquals(NetworkType.NOT_REQUIRED, infos(JobSpecs.CAPTURE_PERIODIC).single().constraints.requiredNetworkType)
    }

    @Test
    fun changingTheIntervalUpdatesTheExistingWork() {
        scheduler(capture = 360).ensureScheduled()
        scheduler(capture = 720).ensureScheduled()

        val info = infos(JobSpecs.CAPTURE_PERIODIC).single()
        assertEquals(TimeUnit.MINUTES.toMillis(720), info.periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun enqueueSyncNowTwiceKeepsASingleRun() {
        val scheduler = scheduler()

        scheduler.enqueueSyncNow()
        scheduler.enqueueSyncNow()

        assertEquals(1, infos(JobSpecs.SYNC_NOW).size)
    }

    @Test
    fun cancelAllCancelsEveryJob() {
        val scheduler = scheduler()
        scheduler.ensureScheduled()
        scheduler.enqueueSyncNow()

        scheduler.cancelAll()

        JobSpecs.allNames.forEach { name ->
            infos(name).forEach { assertEquals(name, WorkInfo.State.CANCELLED, it.state) }
        }
    }

    // Pins the assumption the retry design relies on: a FAILURE result ends
    // only that run of a periodic work; it stays scheduled for the next period.
    // If this fails, JobResultPolicy's retry design no longer holds.
    @Test
    fun aFailedPeriodicRunStaysScheduled() {
        val request = PeriodicWorkRequest.Builder(AlwaysFailWorker::class.java, 15, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork("fail-test", ExistingPeriodicWorkPolicy.KEEP, request).result.get()

        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)

        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfoById(request.id).get()?.state)
    }

    @Test
    fun cancelCaptureLeavesSyncScheduled() {
        val scheduler = scheduler()
        scheduler.ensureScheduled()

        scheduler.cancelCapture()

        assertEquals(WorkInfo.State.CANCELLED, infos(JobSpecs.CAPTURE_PERIODIC).single().state)
        assertEquals(WorkInfo.State.ENQUEUED, infos(JobSpecs.SYNC_PERIODIC).single().state)
    }

    @Test
    fun enqueueOptOutFinishTwiceKeepsASingleRun() {
        val scheduler = scheduler()

        scheduler.enqueueOptOutFinish()
        scheduler.enqueueOptOutFinish()

        assertEquals(1, infos("opt-out-finish").size)
    }

    @Test
    fun cancelAllDoesNotTouchAPendingOptOutFinish() {
        val scheduler = scheduler()
        scheduler.ensureScheduled()
        scheduler.enqueueOptOutFinish()

        scheduler.cancelAll()

        assertEquals(WorkInfo.State.ENQUEUED, infos("opt-out-finish").single().state)
    }
}
