package com.example.smartuse_frontend.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.Instant

private val Context.syncStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_state")

private object SyncStateKeys {
    val CANONICAL_SYNCED_THROUGH_START_TS = longPreferencesKey("canonical_synced_through_start_ts")
    val CANONICAL_SYNCED_THROUGH_EPOCH_ID = stringPreferencesKey("canonical_synced_through_epoch_id")
    val CANONICAL_LAST_SUCCESSFUL_SYNC_AT = stringPreferencesKey("canonical_last_successful_sync_at") // ISO-8601
}

class RealSyncCursorStore(private val context: Context) : SyncCursorStore {

    override suspend fun getCanonicalSyncedThrough(): CanonicalSyncCursor? {
        val prefs = context.syncStateDataStore.data.first()
        val startTs = prefs[SyncStateKeys.CANONICAL_SYNCED_THROUGH_START_TS] ?: return null
        val epochId = prefs[SyncStateKeys.CANONICAL_SYNCED_THROUGH_EPOCH_ID] ?: return null
        return CanonicalSyncCursor(startTs, epochId)
    }

    override suspend fun setCanonicalSyncedThrough(cursor: CanonicalSyncCursor) {
        context.syncStateDataStore.edit { prefs ->
            prefs[SyncStateKeys.CANONICAL_SYNCED_THROUGH_START_TS] = cursor.startTs
            prefs[SyncStateKeys.CANONICAL_SYNCED_THROUGH_EPOCH_ID] = cursor.epochId
        }
    }

    override suspend fun clearCanonicalSyncedThrough() {
        context.syncStateDataStore.edit { prefs ->
            prefs.remove(SyncStateKeys.CANONICAL_SYNCED_THROUGH_START_TS)
            prefs.remove(SyncStateKeys.CANONICAL_SYNCED_THROUGH_EPOCH_ID)
        }
    }

    override suspend fun getLastSuccessfulCanonicalSyncAt(): Instant? =
        context.syncStateDataStore.data.first()[SyncStateKeys.CANONICAL_LAST_SUCCESSFUL_SYNC_AT]?.let(Instant::parse)

    override suspend fun setLastSuccessfulCanonicalSyncAt(instant: Instant) {
        context.syncStateDataStore.edit { prefs ->
            prefs[SyncStateKeys.CANONICAL_LAST_SUCCESSFUL_SYNC_AT] = instant.toString()
        }
    }

    override suspend fun clear() {
        context.syncStateDataStore.edit { it.clear() }
    }
}
