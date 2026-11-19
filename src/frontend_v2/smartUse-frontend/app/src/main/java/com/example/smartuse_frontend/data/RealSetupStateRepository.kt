package com.example.smartuse_frontend.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

class RealSetupStateRepository(private val context: Context) : SetupStateRepository {

    override val isComplete: Flow<Boolean> =
        context.sessionDataStore.data.map { it[SessionKeys.SETUP_COMPLETE] ?: false }

    override suspend fun markComplete() {
        context.sessionDataStore.edit { it[SessionKeys.SETUP_COMPLETE] = true }
    }

    override suspend fun isCompleteNow(): Boolean = isComplete.first()

    override suspend fun historyStart(): Instant? =
        context.sessionDataStore.data.first()[SessionKeys.HISTORY_START]?.let(Instant::parse)

    override suspend fun setHistoryStart(start: Instant?) {
        context.sessionDataStore.edit {
            if (start == null) it.remove(SessionKeys.HISTORY_START) else it[SessionKeys.HISTORY_START] = start.toString()
        }
    }
}
