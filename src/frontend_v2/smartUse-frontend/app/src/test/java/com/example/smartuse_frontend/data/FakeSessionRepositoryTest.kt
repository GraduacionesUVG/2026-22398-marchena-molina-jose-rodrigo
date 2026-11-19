package com.example.smartuse_frontend.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class FakeSessionRepositoryTest {

    @Test
    fun `capture window is open before day 21`() = runTest {
        val signup = Instant.parse("2026-01-01T00:00:00Z")
        val clock = Clock.fixed(signup.plus(20, ChronoUnit.DAYS), ZoneOffset.UTC)
        val repo = FakeSessionRepository(signupInstant = signup, clock = clock)

        assertTrue(repo.isCaptureWindowOpen())
    }

    @Test
    fun `capture window is closed after day 21`() = runTest {
        val signup = Instant.parse("2026-01-01T00:00:00Z")
        val clock = Clock.fixed(signup.plus(22, ChronoUnit.DAYS), ZoneOffset.UTC)
        val repo = FakeSessionRepository(signupInstant = signup, clock = clock)

        assertFalse(repo.isCaptureWindowOpen())
    }
}
