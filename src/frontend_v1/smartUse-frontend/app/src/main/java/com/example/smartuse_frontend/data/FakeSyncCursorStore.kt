package com.example.smartuse_frontend.data

import java.time.Instant

class FakeSyncCursorStore(
    private var cursor: CanonicalSyncCursor? = null,
    private var lastSuccessAt: Instant? = null,
) : SyncCursorStore {
    override suspend fun getCanonicalSyncedThrough(): CanonicalSyncCursor? = cursor

    override suspend fun setCanonicalSyncedThrough(cursor: CanonicalSyncCursor) {
        this.cursor = cursor
    }

    override suspend fun clearCanonicalSyncedThrough() {
        cursor = null
    }

    override suspend fun getLastSuccessfulCanonicalSyncAt(): Instant? = lastSuccessAt

    override suspend fun setLastSuccessfulCanonicalSyncAt(instant: Instant) {
        lastSuccessAt = instant
    }

    override suspend fun clear() {
        cursor = null
        lastSuccessAt = null
    }
}
