package com.example.smartuse_frontend.finalsend

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smartuse_frontend.jobs.JobRuntime

/** The final send (`final-send`): retried with backoff until the backend answers 2xx. */
class FinalSendWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (JobRuntime.finalSendRunner(applicationContext).run()) {
        FinalSendResult.DONE -> Result.success()
        FinalSendResult.RETRY -> Result.retry()
    }
}