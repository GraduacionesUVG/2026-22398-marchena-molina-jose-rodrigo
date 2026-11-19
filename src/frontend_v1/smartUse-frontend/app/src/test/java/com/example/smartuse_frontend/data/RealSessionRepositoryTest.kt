package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.config.ExperimentGroup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class RealSessionRepositoryTest {

    private val signup = Instant.parse("2026-08-01T00:00:00Z")

    private fun session(signupTs: Instant) = PersistedSession(
        userId = "u", authToken = "t", group = ExperimentGroup.CONTROL,
        signupTs = signupTs,
        profile = ParticipantProfile(21, "F", "Psicología", 34),
    )

    private fun at(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    @Test
    fun `window open until signup plus 21 days`() = runTest {
        val repo = RealSessionRepository(FakeCredentialStore(session(signup)), clock = at(signup.plus(20, ChronoUnit.DAYS)))

        assertTrue(repo.isCaptureWindowOpen())
    }

    @Test
    fun `window closed at signup plus 21 days`() = runTest {
        val repo = RealSessionRepository(FakeCredentialStore(session(signup)), clock = at(signup.plus(21, ChronoUnit.DAYS)))

        assertFalse(repo.isCaptureWindowOpen())
    }

    @Test
    fun `no session yet is treated as window open, with nothing to announce`() = runTest {
        val repo = RealSessionRepository(FakeCredentialStore(), cutoffDate = { LocalDate.of(2026, 8, 10) })

        assertTrue(repo.isCaptureWindowOpen())
        assertNull(repo.announcedCutoff())
    }

    @Test
    fun `a stored cutoff before day 21 closes the window at 00 00 Guatemala`() = runTest {
        val cutoff = LocalDate.of(2026, 8, 10) // 2026-08-10T06:00:00Z
        val store = FakeCredentialStore(session(signup))

        val before = RealSessionRepository(store, { cutoff }, at(Instant.parse("2026-08-10T05:59:59Z")))
        val after = RealSessionRepository(store, { cutoff }, at(Instant.parse("2026-08-10T06:00:00Z")))

        assertTrue(before.isCaptureWindowOpen())
        assertFalse(after.isCaptureWindowOpen())
    }

    @Test
    fun `the cutoff is read on every call`() = runTest {
        var cutoff: LocalDate? = null
        val repo = RealSessionRepository(FakeCredentialStore(session(signup)), { cutoff }, at(Instant.parse("2026-08-11T00:00:00Z")))
        assertTrue(repo.isCaptureWindowOpen())

        cutoff = LocalDate.of(2026, 8, 10)

        assertFalse(repo.isCaptureWindowOpen())
    }

    @Test
    fun `the signup is read on every call, so a session saved later counts`() = runTest {
        val store = FakeCredentialStore()
        val repo = RealSessionRepository(store, clock = at(Instant.parse("2026-08-30T00:00:00Z")))
        assertTrue(repo.isCaptureWindowOpen())

        store.saveSession(session(signup))

        assertFalse(repo.isCaptureWindowOpen())
    }

    @Test
    fun `an upcoming cutoff before day 21 is announced`() = runTest {
        val cutoff = LocalDate.of(2026, 8, 10)
        val repo = RealSessionRepository(FakeCredentialStore(session(signup)), { cutoff }, at(signup.plus(2, ChronoUnit.DAYS)))

        assertEquals(cutoff, repo.announcedCutoff())
    }
}
