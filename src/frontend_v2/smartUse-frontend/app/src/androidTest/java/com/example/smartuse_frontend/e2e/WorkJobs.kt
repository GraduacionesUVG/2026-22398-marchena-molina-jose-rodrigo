package com.example.smartuse_frontend.e2e

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.smartuse_frontend.jobs.CycleWorker
import com.example.smartuse_frontend.jobs.JobName
import com.example.smartuse_frontend.jobs.JobState
import com.example.smartuse_frontend.jobs.JobSpecs
import com.example.smartuse_frontend.jobs.RealJobStateStore
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.TimeUnit

/** WorkManager's view of the jobs, and the bridge to JobScheduler ids. */
object WorkJobs {
    private const val FORCED_CAPTURE = "e2e-forced-capture"

    fun infos(context: Context, uniqueName: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get()

    /**
     * The work's job in JobScheduler, waiting for WorkManager to hand it over.
     * Matched on WorkManager's own tags (work id + generation: an UPDATE
     * re-enqueue bumps the generation), in whatever namespace the job sits.
     */
    fun scheduledJob(context: Context, uniqueName: String, timeoutMs: Long = 60_000): ScheduledJob = try {
        await("$uniqueName in JobScheduler", timeoutMs) {
            infos(context, uniqueName).firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.let { info ->
                DeviceShell.scheduledJobs().firstOrNull { it.workSpecId == info.id.toString() && it.generation == info.generation }
            }
        }
    } catch (e: AssertionError) {
        // Say what both sides held, so a device run tells why they didn't match.
        val work = infos(context, uniqueName).map { "${it.id}/gen${it.generation}/${it.state}/attempt${it.runAttemptCount}" }
        throw AssertionError("${e.message}; WorkManager: $work; JobScheduler: ${DeviceShell.scheduledJobs()}", e)
    }

    /**
     * A capture run to force through JobScheduler. The periodic capture can't be
     * forced: it already ran once on its own (no initial delay), and WorkManager
     * drops a periodic run that starts before its next period. A one-time
     * CycleWorker has no such check; the long delay keeps WorkManager's
     * in-process scheduler from running it, so only the forced JobScheduler job does.
     */
    fun captureJobToForce(context: Context): ScheduledJob {
        val request = OneTimeWorkRequestBuilder<CycleWorker>().setInitialDelay(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(FORCED_CAPTURE, ExistingWorkPolicy.REPLACE, request).result.get()
        return scheduledJob(context, FORCED_CAPTURE)
    }

    /** Forces a capture run through JobScheduler (see [captureJobToForce]); returns the instant before it. */
    fun forceCapture(context: Context): Instant {
        val before = Instant.now()
        DeviceShell.forceRunJob(captureJobToForce(context))
        return before
    }

    fun state(context: Context, job: JobName): JobState = runBlocking { RealJobStateStore(context).get(job) }

    /** Waits until [job] recorded any attempt (success or failure) after [since]. */
    fun awaitAttemptAfter(context: Context, job: JobName, since: Instant, timeoutMs: Long = 60_000): JobState =
        await("${job.key} to attempt a run after $since", timeoutMs) {
            state(context, job).takeIf { it.lastAttemptAt?.isAfter(since) == true }
        }

    fun lastSuccess(context: Context, job: JobName): Instant? = runBlocking { RealJobStateStore(context).get(job).lastSuccessAt }

    /** Waits until [job] recorded a successful run after [since] (it may have run on its own). */
    fun awaitCompletedAfter(context: Context, job: JobName, since: Instant, timeoutMs: Long = 60_000): Instant =
        await("${job.key} to complete after $since", timeoutMs) { lastSuccess(context, job)?.takeIf { it > since } }

    /** Waits until no capture work is running, seen on three polls in a row, so the shared checks never read a run halfway. */
    fun awaitQuiet(context: Context, timeoutMs: Long = 60_000) {
        var quietPolls = 0
        await("capture work to go quiet", timeoutMs) {
            val running = (JobSpecs.allNames + FORCED_CAPTURE).any { name ->
                infos(context, name).any { it.state == WorkInfo.State.RUNNING }
            }
            quietPolls = if (running) 0 else quietPolls + 1
            true.takeIf { quietPolls >= 3 }
        }
    }
}
