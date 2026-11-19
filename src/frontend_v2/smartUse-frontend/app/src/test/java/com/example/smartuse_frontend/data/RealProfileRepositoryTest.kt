package com.example.smartuse_frontend.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RealProfileRepositoryTest {

    @Test
    fun `reads the profile persisted at registration`() {
        val profile = ParticipantProfile(23, "Masculino", "Ingeniería", 41)
        val store = FakeCredentialStore(
            PersistedSession(
                userId = "u", authToken = "t",                signupTs = Instant.parse("2026-08-29T00:00:00Z"), profile = profile,
            ),
        )

        assertEquals(profile, RealProfileRepository(store).getProfile())
    }
}
