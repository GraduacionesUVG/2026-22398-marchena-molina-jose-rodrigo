package com.example.smartuse_frontend.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smartuse_frontend.data.db.syncCanonicalToBackend
import com.example.smartuse_frontend.jobs.JobRuntime.toResult

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val scheduler = BackgroundJobScheduler(context)
        // Before the gate, so a newly published (or moved) cutoff counts in this very run.
        JobRuntime.cutoffRefresher(context).refresh()
        val job = SyncJob(runSync = { syncCanonicalToBackend(context) })
        return JobRuntime.executor(context, scheduler)
            .execute(JobName.SYNC, runAttemptCount, job::body)
            .toResult()
    }
}
