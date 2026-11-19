package com.example.smartuse_frontend.network

/**
 * How a failed call should be treated by the jobs.
 */
enum class FailureKind {
    /** Worth retrying (network, timeout, 5xx, 408, 429). */
    TRANSIENT,

    /** Retrying the same request cannot succeed (400, 401, 409, other 4xx). */
    PERMANENT,

    /** 403 capture_period_ended: the study window is over; stop scheduling. */
    STUDY_OVER,
}

fun NetworkResult<*>.failureKind(): FailureKind = when (this) {
    is NetworkResult.IoError -> FailureKind.TRANSIENT
    is NetworkResult.HttpError -> when {
        code == 403 && errorBody?.error == "capture_period_ended" -> FailureKind.STUDY_OVER
        code >= 500 || code == 408 || code == 429 -> FailureKind.TRANSIENT
        else -> FailureKind.PERMANENT
    }
    is NetworkResult.Success -> throw IllegalArgumentException("A Success has no failure kind")
}
