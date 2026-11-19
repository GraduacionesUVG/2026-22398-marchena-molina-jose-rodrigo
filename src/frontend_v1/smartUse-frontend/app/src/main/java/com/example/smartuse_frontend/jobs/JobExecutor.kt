package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.network.FailureKind
import kotlinx.coroutines.CancellationException
import java.time.Clock

/**
 * The one place that decides what a job run means: gate → body → decision →
 * recorded state → side effects. Keeping it here (pure, injectable, unit
 * tested) is what keeps the WorkManager workers thin and the loop bounds
 * honest.
 */
class JobExecutor(
    private val gate: JobGate,
    private val stateStore: JobStateStore,
    private val clock: Clock,
    private val standbyBucket: () -> Int?,
    private val cancelAll: () -> Unit,
    private val onStudyEnded: suspend () -> Unit,
    private val logger: (String) -> Unit,
    private val maxAttempts: Int = JobLimits.MAX_ATTEMPTS_PER_RUN,
) {
    suspend fun execute(job: JobName, runAttemptCount: Int, body: suspend () -> JobOutcome): WorkDecision {
        when (val gateResult = gate.check()) {
            GateResult.OPEN -> Unit
            GateResult.NO_SESSION -> {
                logger("${job.key}: skipped, setup not finished")
                return WorkDecision.SUCCESS
            }
            GateResult.WINDOW_CLOSED -> {
                logger("${job.key}: skipped ($gateResult), ending collection")
                endStudy()
                return WorkDecision.SUCCESS
            }
            GateResult.OPTED_OUT, GateResult.COLLECTION_ENDED -> {
                // The opt-out flow / the ending already owns the notification from here on.
                logger("${job.key}: skipped ($gateResult), cancelling all jobs")
                cancelAll()
                return WorkDecision.SUCCESS
            }
        }

        val outcome = try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JobOutcome.Failed(FailureKind.TRANSIENT, "${e::class.java.simpleName}: ${e.message}")
        }

        val decision = JobResultPolicy.decide(outcome, runAttemptCount, maxAttempts)
        logger("${job.key}: attempt ${runAttemptCount + 1}/$maxAttempts -> $outcome => $decision")
        record(job, outcome, decision)

        if (outcome is JobOutcome.Failed && outcome.kind == FailureKind.STUDY_OVER) {
            logger("${job.key}: study is over, ending collection")
            endStudy()
        }
        return decision
    }

    /** Runs the ending (mark, cancel, erase, notice); whatever happens there, nothing is left scheduled. */
    private suspend fun endStudy() {
        try {
            onStudyEnded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("ending collection failed: ${e::class.java.simpleName}: ${e.message}")
        }
        cancelAll()
    }

    private suspend fun record(job: JobName, outcome: JobOutcome, decision: WorkDecision) {
        val now = clock.instant()
        val bucket = standbyBucket()
        val state = stateStore.get(job)
        val updated = when (outcome) {
            is JobOutcome.Success -> state.afterSuccess(now, bucket)
            is JobOutcome.Failed -> {
                val failure = JobFailure(outcome.kind, outcome.message)
                if (decision == WorkDecision.FAILURE) state.afterFailedRun(now, failure, bucket)
                else state.afterAttempt(now, failure, bucket)
            }
        }
        stateStore.put(job, updated)
    }
}
