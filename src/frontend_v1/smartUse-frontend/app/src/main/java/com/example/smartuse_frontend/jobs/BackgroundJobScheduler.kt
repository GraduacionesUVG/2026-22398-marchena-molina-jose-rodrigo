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
import java.util.concurrent.TimeUnit

private const val TAG = "JobScheduler"
private const val OPT_OUT_FINISH = "opt-out-finish"

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
        workManager.cancelUniqueWork(JobSpecs.LEGACY_PERMISSION_WATCH_PERIODIC)
    }

    /** Called by the capture job when it finishes; a pending run is kept, never duplicated. */
    fun enqueueSyncNow() {
        val spec = JobSpecs.syncNow
        val request = OneTimeWorkRequest.Builder(workerFor(spec.kind))
            .setConstraints(constraints(spec.requiresNetwork))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, JobLimits.BACKOFF_INITIAL.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(spec.uniqueName, ExistingWorkPolicy.KEEP, request)
        logger("enqueueSyncNow")
    }

    /** Cancels only capture — used by soft opt-out, which must keep sync running to drain. */
    fun cancelCapture() {
        workManager.cancelUniqueWork(JobSpecs.CAPTURE_PERIODIC)
        logger("cancelCapture")
    }

    /**
     * Deliberately not part of [JobSpecs.allNames] / [cancelAll] — this is the
     * one job that must keep running after opt-out, to finish confirming it
     * with the backend. KEEP: a pending retry is never duplicated.
     */
    fun enqueueOptOutFinish() {
        workManager.enqueueUniqueWork(OPT_OUT_FINISH, ExistingWorkPolicy.KEEP, optOutFinishRequest())
        logger("enqueueOptOutFinish")
    }

    fun cancelAll() {
        JobSpecs.allNames.forEach { workManager.cancelUniqueWork(it) }
        logger("cancelAll")
    }

    private fun workerFor(kind: JobKind): Class<out ListenableWorker> = when (kind) {
        JobKind.CAPTURE -> CaptureWorker::class.java
        JobKind.SYNC -> SyncWorker::class.java
    }

    private fun constraints(requiresNetwork: Boolean): Constraints =
        Constraints.Builder().apply {
            if (requiresNetwork) setRequiredNetworkType(NetworkType.CONNECTED)
        }.build()
}

/** The opt-out finish job: needs the network, retries linearly until it succeeds. */
internal fun optOutFinishRequest(): OneTimeWorkRequest =
    OneTimeWorkRequest.Builder(OptOutFinishWorker::class.java)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.LINEAR, JobLimits.OPT_OUT_BACKOFF.toMillis(), TimeUnit.MILLISECONDS)
        .build()
