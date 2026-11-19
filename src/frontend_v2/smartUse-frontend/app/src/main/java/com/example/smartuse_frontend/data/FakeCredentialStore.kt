package com.example.smartuse_frontend.data

import java.time.Instant

class FakeCredentialStore(initial: PersistedSession? = null) : CredentialStore {

    private var session: PersistedSession? = initial
    var saveCount: Int = 0
        private set

    override suspend fun saveSession(session: PersistedSession) {
        this.session = session
        saveCount++
    }

    override suspend fun userId(): String? = session?.userId
    override suspend fun authToken(): String? = session?.authToken
    override suspend fun signupTimestamp(): Instant? = session?.signupTs
    override suspend fun profile(): ParticipantProfile? = session?.profile
    override suspend fun hasCredentials(): Boolean = session != null

    override suspend fun clear() {
        session = null
    }
}
