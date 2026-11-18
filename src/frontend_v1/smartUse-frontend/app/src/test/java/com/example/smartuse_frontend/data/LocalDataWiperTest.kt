package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.config.ExperimentGroup
import com.example.smartuse_frontend.jobs.FakeJobStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private class FailingCredentialStore : CredentialStore by FakeCredentialStore() {
    override suspend fun clear() = throw IllegalStateException("boom")
}

class LocalDataWiperTest {

    @Test
    fun `wipe clears the credential store, sync cursor store, job history, keys and databases`() = runTest {
        val credentialStore = FakeCredentialStore(
            PersistedSession(
                userId = "u", authToken = "t", group = ExperimentGroup.CONTROL,
                signupTs = Instant.parse("2026-09-01T00:00:00Z"),
                profile = ParticipantProfile(age = 21, gender = "F", universityMajor = "X", sasSvScore = 1),
            ),
        )
        val syncCursorStore = FakeSyncCursorStore(cursor = CanonicalSyncCursor(1L, "e-1"))
        val jobStateStore = FakeJobStateStore()
        val keystoreKeyManager = FakeKeystoreKeyManager()
        var databasesDeleted = 0

        LocalDataWiper(
            credentialStore = credentialStore,
            syncCursorStore = syncCursorStore,
            jobStateStore = jobStateStore,
            keystoreKeyManager = keystoreKeyManager,
            deleteDatabases = { databasesDeleted++ },
        ).wipe()

        assertEquals(null, credentialStore.userId())
        assertEquals(null, syncCursorStore.getCanonicalSyncedThrough())
        assertTrue(keystoreKeyManager.destroyed)
        assertEquals(1, databasesDeleted)
    }

    @Test
    fun `one failing step does not skip the rest`() = runTest {
        val syncCursorStore = FakeSyncCursorStore(cursor = CanonicalSyncCursor(1L, "e-1"))
        var databasesDeleted = 0

        LocalDataWiper(
            credentialStore = FailingCredentialStore(),
            syncCursorStore = syncCursorStore,
            jobStateStore = FakeJobStateStore(),
            keystoreKeyManager = FakeKeystoreKeyManager(),
            deleteDatabases = { databasesDeleted++ },
            logger = {},
        ).wipe()

        assertEquals(null, syncCursorStore.getCanonicalSyncedThrough())
        assertEquals(1, databasesDeleted)
    }
}
