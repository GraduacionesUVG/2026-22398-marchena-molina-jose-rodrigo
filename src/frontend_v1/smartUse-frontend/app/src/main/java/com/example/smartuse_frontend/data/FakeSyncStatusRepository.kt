package com.example.smartuse_frontend.data

import java.time.Instant

class FakeSyncStatusRepository(
    private val lastSync: Instant? = Instant.now().minusSeconds(3600),
) : SyncStatusRepository {
    override fun lastSuccessfulCanonicalSync(): Instant? = lastSync
}
