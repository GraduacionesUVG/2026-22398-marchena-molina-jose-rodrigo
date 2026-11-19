package com.example.smartuse_frontend.data

import java.time.Instant

sealed interface RegisterOutcome {
    data class Registered(
        val userId: String,
        val authToken: String,
        val signupTs: Instant,
    ) : RegisterOutcome

    /** HTTP 400 — the request itself is wrong; sending it again unchanged will not help. */
    data class Rejected(val message: String) : RegisterOutcome

    /** IO error, timeout, or 5xx — the same request may succeed later. */
    object Retryable : RegisterOutcome
}

interface RegistrationRepository {
    /** Calls POST /users. Callers must not invoke this again once it has returned [RegisterOutcome.Registered]. */
    suspend fun register(draft: SetupDraft): RegisterOutcome
}
