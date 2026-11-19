package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.BuildConfig
import java.time.Duration

/**
 * Every bound on the background jobs, in one place. The constructor values are
 * tunable in debug builds via local.properties; everything in the companion is
 * fixed.
 */
data class JobLimits(
    val cycleIntervalMinutes: Long,
    val stuckAfterFailedRuns: Int,
) {
    /** WorkManager silently raises periodic intervals under 15 minutes to 15. */
    val effectiveCycleIntervalMinutes: Long get() = maxOf(cycleIntervalMinutes, MIN_PERIODIC_MINUTES)

    fun describe(): String = "cycle every ${effectiveCycleIntervalMinutes}min"

    companion object {
        const val MIN_PERIODIC_MINUTES = 15L
        const val MAX_ATTEMPTS_PER_RUN = 5
        val BACKOFF_INITIAL: Duration = Duration.ofSeconds(30)

        /** First retry of the final send; WorkManager doubles it up to its 5 h cap. */
        val FINAL_SEND_BACKOFF_INITIAL: Duration = Duration.ofSeconds(30)

        // `t`: a placeholder until the inference interval is set.
        private const val DEFAULT_CYCLE_INTERVAL_MINUTES = 15L
        private const val DEFAULT_STUCK_AFTER_FAILED_RUNS = 3

        /** Values <= 0 (missing/invalid config) fall back to the defaults. */
        fun fromConfig(cycleIntervalMinutes: Int, stuckAfterFailedRuns: Int) = JobLimits(
            cycleIntervalMinutes = cycleIntervalMinutes.takeIf { it > 0 }?.toLong() ?: DEFAULT_CYCLE_INTERVAL_MINUTES,
            stuckAfterFailedRuns = stuckAfterFailedRuns.takeIf { it > 0 } ?: DEFAULT_STUCK_AFTER_FAILED_RUNS,
        )

        val current: JobLimits by lazy {
            fromConfig(BuildConfig.JOB_CYCLE_INTERVAL_MINUTES, BuildConfig.JOB_STUCK_AFTER_FAILED_RUNS)
        }
    }
}
