package com.example.smartuse_frontend.e2e

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.jobs.BackgroundJobScheduler
import com.example.smartuse_frontend.jobs.JobName
import com.example.smartuse_frontend.jobs.JobSpecs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class CaptureSyncTest {

    @get:Rule val backend = FakeBackend()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aScheduledCaptureRunFillsTheDatabaseAndTheSyncSendsItAll() {
        Seed.registeredSession(context, backend.token)
        DeviceShell.grantUsageAccess()
        DeviceShell.grantNotifications()
        BackgroundJobScheduler(context).ensureScheduled()
        WorkJobs.awaitQuiet(context) // the periodic jobs' own first runs
        Usage.generate(context)

        val beforeCapture = Instant.now()
        DeviceShell.forceRunJob(WorkJobs.captureJobToForce(context))
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, beforeCapture)

        val epochs = DbInspector.epochs(context)
        assertTrue("at least 3 epochs after real screen events, got ${epochs.size}", epochs.size >= 3)
        val notifications = (context.getSystemService(NotificationManager::class.java)).activeNotifications
        assertTrue("the recording notification is posted", notifications.any { it.id == 3001 })

        // The capture run enqueued sync-now; it may run by itself, otherwise force it.
        val syncDone = runCatching { WorkJobs.awaitCompletedAfter(context, JobName.SYNC, beforeCapture, timeoutMs = 20_000) }
        if (syncDone.isFailure) {
            DeviceShell.forceRunJob(WorkJobs.scheduledJob(context, JobSpecs.SYNC_NOW))
            WorkJobs.awaitCompletedAfter(context, JobName.SYNC, beforeCapture)
        }
        WorkJobs.awaitQuiet(context)
        assertTrue("records reached the backend", backend.sentRecords().size >= 2)
        BackendRecorder.assertContract(backend)
        DbInspector.assertConsistent(context, backend)

        // A second run with nothing new: nothing duplicated, no cursor moves back.
        val cursorBefore = backend.cursor
        val countBefore = DbInspector.epochs(context).size
        val beforeSecond = Instant.now()
        DeviceShell.forceRunJob(WorkJobs.captureJobToForce(context))
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, beforeSecond)
        WorkJobs.awaitQuiet(context) // the sync-now this capture enqueued
        assertTrue("epochs never disappear", DbInspector.epochs(context).size >= countBefore)
        cursorBefore?.let { before ->
            val now = backend.cursor!!
            assertTrue("sync cursor moved backwards", Instant.parse(now.startTs) >= Instant.parse(before.startTs))
        }
        BackendRecorder.assertContract(backend)
        DbInspector.assertConsistent(context, backend)
    }
}
