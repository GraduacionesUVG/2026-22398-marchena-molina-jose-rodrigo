package com.example.smartuse_frontend.jobs

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.example.smartuse_frontend.finalsend.FinalSendWorker
import java.util.concurrent.TimeUnit

private const val TAG = "JobScheduler"

/**
 * Translates [JobSpecs] into WorkManager unique work. Everything here is
 * idempotent: `ensureScheduled()` can be called on every launch.
 */
class BackgroundJobScheduler(
    context: Context,
    private val limits: JobLimits = JobLimits.current,
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun ensureScheduled() {
        logger("ensureScheduled: ${limits.describe()}")
        JobSpecs.retiredNames.forEach { workManager.cancelUniqueWork(it) }
        JobSpecs.periodic(limits).forEach { spec ->
            val request = PeriodicWorkRequest.Builder(workerFor(spec.kind), spec.intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints(spec.requiresNetwork))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, JobLimits.BACKOFF_INITIAL.toMillis(), TimeUnit.MILLISECONDS)
                .build()
            workManager.enqueueUniquePeriodicWork(
                spec.uniqueName,
                when (spec.policy) {
                    SchedulePolicy.KEEP -> ExistingPeriodicWorkPolicy.KEEP
                    SchedulePolicy.UPDATE -> ExistingPeriodicWorkPolicy.UPDATE
                },
                request,
            )
        }
    }

    /** The final send: unique one-time work, kept if already queued; cancelAll() never touches it. */
    fun ensureFinalSend() {
        val request = OneTimeWorkRequest.Builder(FinalSendWorker::class.java)
            .setConstraints(constraints(requiresNetwork = true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, JobLimits.FINAL_SEND_BACKOFF_INITIAL.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(JobSpecs.FINAL_SEND, ExistingWorkPolicy.KEEP, request)
        logger("ensureFinalSend")
    }

    fun cancelAll() {
        JobSpecs.allNames.forEach { workManager.cancelUniqueWork(it) }
        logger("cancelAll")
    }

    private fun workerFor(kind: JobKind): Class<out ListenableWorker> = when (kind) {
        JobKind.CYCLE -> CycleWorker::class.java
    }

    private fun constraints(requiresNetwork: Boolean): Constraints =
        Constraints.Builder().apply {
            if (requiresNetwork) setRequiredNetworkType(NetworkType.CONNECTED)
        }.build()
}

