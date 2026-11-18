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
import org.junit.Assert.assertTrue
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

    private fun scheduler(interval: Int = 360) =
        BackgroundJobScheduler(context, JobLimits.fromConfig(interval, 3)) {}

    @Test
    fun ensureScheduledTwiceYieldsOnePeriodicCapture() {
        val scheduler = scheduler()

        scheduler.ensureScheduled()
        scheduler.ensureScheduled()

        assertEquals(1, infos(JobSpecs.CYCLE_PERIODIC).size)
    }

    @Test
    fun captureDoesNotRequireANetwork() {
        scheduler().ensureScheduled()

        assertEquals(NetworkType.NOT_REQUIRED, infos(JobSpecs.CYCLE_PERIODIC).single().constraints.requiredNetworkType)
    }

    @Test
    fun changingTheIntervalUpdatesTheExistingWork() {
        scheduler(interval = 360).ensureScheduled()
        scheduler(interval = 720).ensureScheduled()

        val info = infos(JobSpecs.CYCLE_PERIODIC).single()
        assertEquals(TimeUnit.MINUTES.toMillis(720), info.periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun ensureScheduledCancelsTheRetiredCaptureWork() {
        val old = PeriodicWorkRequest.Builder(AlwaysFailWorker::class.java, 6, TimeUnit.HOURS).build()
        workManager.enqueueUniquePeriodicWork(JobSpecs.RETIRED_CAPTURE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, old).result.get()

        scheduler().ensureScheduled()

        assertTrue(infos(JobSpecs.RETIRED_CAPTURE_PERIODIC).all { it.state == WorkInfo.State.CANCELLED })
        assertEquals(1, infos(JobSpecs.CYCLE_PERIODIC).size)
    }

    @Test
    fun cancelAllCancelsEveryJob() {
        val scheduler = scheduler()
        scheduler.ensureScheduled()

        scheduler.cancelAll()

        JobSpecs.allNames.forEach { name ->
            infos(name).forEach { assertEquals(name, WorkInfo.State.CANCELLED, it.state) }
        }
    }

    // Pins the assumption the retry design relies on: a FAILURE result ends
    // only that run of a periodic work; it stays scheduled for the next period.
    @Test
    fun aFailedPeriodicRunStaysScheduled() {
        val request = PeriodicWorkRequest.Builder(AlwaysFailWorker::class.java, 15, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork("fail-test", ExistingPeriodicWorkPolicy.KEEP, request).result.get()

        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)

        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfoById(request.id).get()?.state)
    }
}
