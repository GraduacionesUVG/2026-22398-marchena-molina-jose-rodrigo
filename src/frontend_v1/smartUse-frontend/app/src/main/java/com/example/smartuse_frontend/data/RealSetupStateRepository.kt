package com.example.smartuse_frontend.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class RealSetupStateRepository(private val context: Context) : SetupStateRepository {

    override val isComplete: Flow<Boolean> =
        context.sessionDataStore.data.map { it[SessionKeys.SETUP_COMPLETE] ?: false }

    override suspend fun markComplete() {
        context.sessionDataStore.edit { it[SessionKeys.SETUP_COMPLETE] = true }
    }

    override suspend fun isCompleteNow(): Boolean = isComplete.first()
}
