package com.example.smartuse_frontend.data

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Tracks whether first-launch setup has finished. `MainActivity` gates on this. */
interface SetupStateRepository {
    val isComplete: Flow<Boolean>
    suspend fun markComplete()
    suspend fun isCompleteNow(): Boolean

    /** Where capture starts when the participant consented to earlier usage; null otherwise. */
    suspend fun historyStart(): Instant?
    suspend fun setHistoryStart(start: Instant?)
}
