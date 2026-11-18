package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.network.FailureKind
import java.time.Instant

enum class JobName(val key: String) {
    CAPTURE("capture"),
    SYNC("sync"),
}

data class JobFailure(val kind: FailureKind, val message: String)

/** What the Status page needs to know about a job. */
data class JobHealth(val lastSuccessAt: Instant?, val stuckSince: Instant?)

/**
 * Fixed-size health record for one job. A *run* ends in success or failure
 * (permanent, or gave up after the attempt limit); an attempt that WorkManager
 * will retry only updates `lastAttemptAt` / `lastFailure`, so
 * [consecutiveFailedRuns] counts runs, not attempts.
 */
data class JobState(
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val consecutiveFailedRuns: Int = 0,
    val firstFailureAt: Instant? = null,
    val lastFailure: JobFailure? = null,
    val lastStandbyBucket: Int? = null,
) {
    fun isStuck(threshold: Int): Boolean = consecutiveFailedRuns >= threshold

    fun afterSuccess(now: Instant, bucket: Int?): JobState = copy(
        lastAttemptAt = now,
        lastSuccessAt = now,
        consecutiveFailedRuns = 0,
        firstFailureAt = null,
        lastFailure = null,
        lastStandbyBucket = bucket,
    )

    /** An attempt that neither ended the run in success nor in failure (will retry, or study over). */
    fun afterAttempt(now: Instant, failure: JobFailure, bucket: Int?): JobState = copy(
        lastAttemptAt = now,
        lastFailure = failure,
        lastStandbyBucket = bucket,
    )

    fun afterFailedRun(now: Instant, failure: JobFailure, bucket: Int?): JobState = copy(
        lastAttemptAt = now,
        consecutiveFailedRuns = consecutiveFailedRuns + 1,
        firstFailureAt = firstFailureAt ?: now,
        lastFailure = failure,
        lastStandbyBucket = bucket,
    )

    fun toHealth(threshold: Int): JobHealth = JobHealth(
        lastSuccessAt = lastSuccessAt,
        stuckSince = if (isStuck(threshold)) firstFailureAt else null,
    )
}
