package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.BuildConfig
import java.time.Duration

/**
 * Every bound on the background jobs, in one place. The constructor values are
 * tunable in debug builds via local.properties; everything in
 * the companion is fixed.
 */
data class JobLimits(
    val captureIntervalMinutes: Long,
    val syncIntervalMinutes: Long,
    val syncBatchSize: Int,
    val stuckAfterFailedRuns: Int,
) {
    /** WorkManager silently raises periodic intervals under 15 minutes to 15. */
    val effectiveCaptureIntervalMinutes: Long get() = maxOf(captureIntervalMinutes, MIN_PERIODIC_MINUTES)
    val effectiveSyncIntervalMinutes: Long get() = maxOf(syncIntervalMinutes, MIN_PERIODIC_MINUTES)

    fun describe(): String =
        "capture every ${effectiveCaptureIntervalMinutes}min, sync every ${effectiveSyncIntervalMinutes}min, " +
            "batch $syncBatchSize"

    companion object {
        const val MIN_PERIODIC_MINUTES = 15L
        // The backend refuses more than 500 records per request (413, nothing stored).
        const val SYNC_BATCH_CEILING = 500
        const val MAX_BATCHES_PER_RUN = 50
        const val MAX_ATTEMPTS_PER_RUN = 5
        val RUN_TIME_BUDGET: Duration = Duration.ofMinutes(3)
        val BACKOFF_INITIAL: Duration = Duration.ofSeconds(30)

        /** Opt-out finish retries: linear (10, 20, 30 min…; WorkManager caps each wait at 5 h), never giving up. */
        val OPT_OUT_BACKOFF: Duration = Duration.ofMinutes(10)

        private const val DEFAULT_CAPTURE_INTERVAL_MINUTES = 360L
        private const val DEFAULT_SYNC_INTERVAL_MINUTES = 360L
        private const val DEFAULT_SYNC_BATCH_SIZE = 500
        private const val DEFAULT_STUCK_AFTER_FAILED_RUNS = 3

        /** Values <= 0 (missing/invalid config) fall back to the defaults; batch size is clamped to the ceiling. */
        fun fromConfig(
            captureIntervalMinutes: Int,
            syncIntervalMinutes: Int,
            syncBatchSize: Int,
            stuckAfterFailedRuns: Int,
        ) = JobLimits(
            captureIntervalMinutes = captureIntervalMinutes.takeIf { it > 0 }?.toLong() ?: DEFAULT_CAPTURE_INTERVAL_MINUTES,
            syncIntervalMinutes = syncIntervalMinutes.takeIf { it > 0 }?.toLong() ?: DEFAULT_SYNC_INTERVAL_MINUTES,
            syncBatchSize = syncBatchSize.takeIf { it > 0 }?.coerceAtMost(SYNC_BATCH_CEILING) ?: DEFAULT_SYNC_BATCH_SIZE,
            stuckAfterFailedRuns = stuckAfterFailedRuns.takeIf { it > 0 } ?: DEFAULT_STUCK_AFTER_FAILED_RUNS,
        )

        val current: JobLimits by lazy {
            fromConfig(
                BuildConfig.JOB_CAPTURE_INTERVAL_MINUTES,
                BuildConfig.JOB_SYNC_INTERVAL_MINUTES,
                BuildConfig.JOB_SYNC_BATCH_SIZE,
                BuildConfig.JOB_STUCK_AFTER_FAILED_RUNS,
            )
        }
    }
}
