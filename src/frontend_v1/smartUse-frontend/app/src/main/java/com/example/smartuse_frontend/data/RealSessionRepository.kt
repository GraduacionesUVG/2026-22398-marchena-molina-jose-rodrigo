package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.study.StudyWindow
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Real [SessionRepository]. The signup and the cutoff are read on every call
 * (small DataStore reads): the cutoff can change at any sync, and the signup
 * only appears at registration, so neither may be cached.
 */
class RealSessionRepository(
    private val credentialStore: CredentialStore,
    private val cutoffDate: suspend () -> LocalDate? = { null },
    private val clock: Clock = Clock.systemUTC(),
) : SessionRepository {

    override suspend fun isCaptureWindowOpen(): Boolean {
        val signup = credentialStore.signupTimestamp() ?: return true
        return StudyWindow.isOpen(signup, cutoffDate(), Instant.now(clock))
    }

    override suspend fun announcedCutoff(): LocalDate? {
        val signup = credentialStore.signupTimestamp() ?: return null
        return StudyWindow.announcedCutoff(signup, cutoffDate(), Instant.now(clock))
    }
}
