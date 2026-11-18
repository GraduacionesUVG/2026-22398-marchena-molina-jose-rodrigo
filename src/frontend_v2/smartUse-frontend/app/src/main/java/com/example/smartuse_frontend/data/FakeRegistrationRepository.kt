package com.example.smartuse_frontend.data

import java.time.Instant

class FakeRegistrationRepository(
    private val outcome: RegisterOutcome = RegisterOutcome.Registered(
        userId = "u-fake",
        authToken = "tok-fake",
        signupTs = Instant.parse("2026-08-29T00:00:00Z"),
    ),
) : RegistrationRepository {
    var calls: Int = 0
        private set

    override suspend fun register(draft: SetupDraft): RegisterOutcome {
        calls++
        return outcome
    }
}
