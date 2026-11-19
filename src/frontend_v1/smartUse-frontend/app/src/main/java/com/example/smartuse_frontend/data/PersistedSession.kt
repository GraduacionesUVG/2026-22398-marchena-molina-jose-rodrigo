package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.config.ExperimentGroup
import java.time.Instant

/** Everything written to CredentialStore at registration success. */
data class PersistedSession(
    val userId: String,
    val authToken: String,
    val group: ExperimentGroup,
    val signupTs: Instant,
    val profile: ParticipantProfile,
)
