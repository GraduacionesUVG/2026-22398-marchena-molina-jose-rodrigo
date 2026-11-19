package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.config.ExperimentGroup
import java.time.Instant

/**
 * Durable store for the registered session. `auth_token` is encrypted at rest
 * by the real implementation; everything else is plaintext.
 */
interface CredentialStore {
    suspend fun saveSession(session: PersistedSession)
    suspend fun userId(): String?
    suspend fun authToken(): String?
    suspend fun experimentGroup(): ExperimentGroup?
    suspend fun signupTimestamp(): Instant?
    suspend fun profile(): ParticipantProfile?

    /** True once a registration response has been persisted. Drives setup resume. */
    suspend fun hasCredentials(): Boolean

    /** Wipes the session (including its Keystore key) once opt-out is confirmed. No further network calls follow. */
    suspend fun clear()
}
