package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.network.FailureKind

/** What a job body reports; [JobExecutor] turns it into a [WorkDecision]. */
sealed interface JobOutcome {
    data class Success(val note: String? = null) : JobOutcome
    data class Failed(val kind: FailureKind, val message: String) : JobOutcome
}

/** What the WorkManager worker should return. */
enum class WorkDecision { SUCCESS, RETRY, FAILURE }
