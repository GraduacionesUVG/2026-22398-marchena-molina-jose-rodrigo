package com.example.smartuse_frontend.data

import java.time.Instant

/** Everything written to CredentialStore at registration success. */
data class PersistedSession(
    val userId: String,
    val authToken: String,
    val signupTs: Instant,
    val profile: ParticipantProfile,
)
