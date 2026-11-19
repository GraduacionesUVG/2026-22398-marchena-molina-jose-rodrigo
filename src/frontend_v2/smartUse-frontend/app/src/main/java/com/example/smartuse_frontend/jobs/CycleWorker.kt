package com.example.smartuse_frontend.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smartuse_frontend.data.RealPermissionChecker
import com.example.smartuse_frontend.data.db.runFullPipeline
import com.example.smartuse_frontend.jobs.JobRuntime.toResult
import com.example.smartuse_frontend.service.RealStudyServiceController
import java.time.Instant

/** The periodic cycle (every `t`): capture, then the profile freeze. See StudyCycle. */
class CycleWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val scheduler = BackgroundJobScheduler(context)
        // Before the gate, so a newly published (or moved) cutoff counts in this very run.
        JobRuntime.cutoffRefresher(context).refresh()
        val executor = JobRuntime.executor(context, scheduler)
        val capture = CaptureJob(
            refreshNotice = { JobRuntime.refreshRecordingNotice(context) },
            hasUsageAccess = { RealPermissionChecker(context).hasUsageAccess() },
            runPipeline = { runFullPipeline(context) },
            logger = JobRuntime::log,
        )
        val freezer = JobRuntime.profileFreezer(context)
        return StudyCycle(
            isStudyLive = { JobRuntime.isStudyLive(context) },
            startService = { RealStudyServiceController(context).start() },
            runCapture = { executor.execute(JobName.CAPTURE, runAttemptCount, capture::body) },
            // Only while live: after the ending canonical.db is erased, not rebuilt.
            freezeProfiles = { if (JobRuntime.isStudyLive(context)) freezer.tryFreeze(Instant.now().epochSecond.toDouble()) },
            logger = JobRuntime::log,
        ).run().toResult()
    }
}
