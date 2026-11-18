package com.example.smartuse_frontend.data

import kotlinx.coroutines.runBlocking

/**
 * Real [ProfileRepository] reading the profile captured at setup. One-time
 * blocking read (see [RealSessionRepository] for the rationale).
 */
class RealProfileRepository(
    private val credentialStore: CredentialStore,
) : ProfileRepository {

    private val cachedProfile: ParticipantProfile by lazy {
        runBlocking {
            credentialStore.profile()
                ?: ParticipantProfile(age = 0, gender = "", universityMajor = "", sasSvScore = 0)
        }
    }

    override fun getProfile(): ParticipantProfile = cachedProfile
}
