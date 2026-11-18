package com.example.smartuse_frontend.data

import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * Real [SyncStatusRepository]. Reads fresh each call (unlike
 * [RealProfileRepository]'s one-time cache) since this value changes over
 * the life of the app as syncs happen.
 */
class RealSyncStatusRepository(private val syncCursorStore: SyncCursorStore) : SyncStatusRepository {
    override fun lastSuccessfulCanonicalSync(): Instant? =
        runBlocking { syncCursorStore.getLastSuccessfulCanonicalSyncAt() }
}
