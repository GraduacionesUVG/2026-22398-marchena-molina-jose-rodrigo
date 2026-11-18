package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.network.FailureKind

/**
 * The retry rules, as a pure function. WorkManager's own retry is unbounded, so
 * this is what makes it bounded: transient failures retry until
 * [maxAttempts] (`runAttemptCount` is WorkManager's 0-based count), everything
 * else ends the run immediately. For periodic work a FAILURE ends only that
 * run; the schedule continues into the next period.
 */
object JobResultPolicy {
    fun decide(
        outcome: JobOutcome,
        runAttemptCount: Int,
        maxAttempts: Int = JobLimits.MAX_ATTEMPTS_PER_RUN,
    ): WorkDecision = when (outcome) {
        is JobOutcome.Success -> WorkDecision.SUCCESS
        is JobOutcome.Failed -> when (outcome.kind) {
            FailureKind.TRANSIENT ->
                if (runAttemptCount + 1 < maxAttempts) WorkDecision.RETRY else WorkDecision.FAILURE
            FailureKind.PERMANENT -> WorkDecision.FAILURE
            FailureKind.STUDY_OVER -> WorkDecision.SUCCESS
        }
    }
}
