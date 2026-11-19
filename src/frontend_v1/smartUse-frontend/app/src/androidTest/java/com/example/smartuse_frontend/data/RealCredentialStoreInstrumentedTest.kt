package com.example.smartuse_frontend.data

import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.smartuse_frontend.config.ExperimentGroup
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class RealCredentialStoreInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val session = PersistedSession(
        userId = "u-42",
        authToken = "secret-token-value",
        group = ExperimentGroup.CONTROL,
        signupTs = Instant.parse("2026-08-29T10:00:00Z"),
        profile = ParticipantProfile(23, "Masculino", "Ingeniería", 41),
    )

    @Before fun clear(): Unit = runBlocking {
        context.sessionDataStore.edit { it.clear() }
    }

    @Test
    fun authToken_round_trips_through_keystore() = runBlocking {
        RealCredentialStore(context).saveSession(session)

        // A fresh instance simulates a process restart.
        assertEquals("secret-token-value", RealCredentialStore(context).authToken())
        assertEquals("u-42", RealCredentialStore(context).userId())
        assertEquals(session.signupTs, RealCredentialStore(context).signupTimestamp())
    }

    @Test
    fun ciphertext_on_disk_is_not_the_plaintext_token() = runBlocking {
        RealCredentialStore(context).saveSession(session)

        val stored = context.sessionDataStore.data.first()[SessionKeys.AUTH_TOKEN_CIPHERTEXT]
        assertTrue(stored != null && stored.isNotBlank())
        assertFalse(stored!!.contains("secret-token-value"))
    }
}
