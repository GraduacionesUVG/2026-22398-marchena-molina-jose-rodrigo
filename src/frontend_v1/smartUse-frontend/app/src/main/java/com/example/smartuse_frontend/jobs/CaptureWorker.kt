package com.example.smartuse_frontend.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smartuse_frontend.data.RealPermissionChecker
import com.example.smartuse_frontend.data.db.runFullPipeline
import com.example.smartuse_frontend.jobs.JobRuntime.toResult

class CaptureWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val scheduler = BackgroundJobScheduler(context)
        // Before the gate, so a newly published (or moved) cutoff counts in this very run.
        JobRuntime.cutoffRefresher(context).refresh()
        val job = CaptureJob(
            refreshNotice = { JobRuntime.refreshRecordingNotice(context) },
            hasUsageAccess = { RealPermissionChecker(context).hasUsageAccess() },
            runPipeline = { runFullPipeline(context) },
            enqueueSyncNow = { scheduler.enqueueSyncNow() },
            logger = JobRuntime::log,
        )
        return JobRuntime.executor(context, scheduler)
            .execute(JobName.CAPTURE, runAttemptCount, job::body)
            .toResult()
    }
}
