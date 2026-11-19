package com.example.smartuse_frontend.data

import kotlinx.coroutines.flow.Flow

/** Tracks whether first-launch setup has finished. `MainActivity` gates on this. */
interface SetupStateRepository {
    val isComplete: Flow<Boolean>
    suspend fun markComplete()
    suspend fun isCompleteNow(): Boolean
}
