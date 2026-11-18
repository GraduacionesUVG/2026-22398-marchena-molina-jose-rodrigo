package com.example.smartuse_frontend.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FakeCredentialStoreTest {

    private val session = PersistedSession(
        userId = "u-1",
        authToken = "tok-1",
        signupTs = Instant.parse("2026-08-29T12:00:00Z"),
        profile = ParticipantProfile(21, "Femenino", "Psicología", 34),
    )

    @Test
    fun `starts empty`() = runTest {
        val store = FakeCredentialStore()
        assertFalse(store.hasCredentials())
        assertNull(store.userId())
    }

    @Test
    fun `saveSession makes every field readable`() = runTest {
        val store = FakeCredentialStore()

        store.saveSession(session)

        assertTrue(store.hasCredentials())
        assertEquals("u-1", store.userId())
        assertEquals("tok-1", store.authToken())
        assertEquals(session.signupTs, store.signupTimestamp())
        assertEquals(session.profile, store.profile())
        assertEquals(1, store.saveCount)
    }
}
