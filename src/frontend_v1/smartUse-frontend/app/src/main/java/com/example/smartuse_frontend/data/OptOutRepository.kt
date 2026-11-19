package com.example.smartuse_frontend.data

import kotlinx.coroutines.flow.Flow

sealed interface OptOutResult {
    object Success : OptOutResult
    data class Failure(val message: String) : OptOutResult

    /** Hard opt-out confirmed after the end moment: the ending ran instead, nothing was sent. */
    object CollectionEnded : OptOutResult
}

/**
 * `optOutSoft()`/`optOutHard()` stop capture/sync locally before returning and
 * always return `Success` synchronously — the backend confirmation and local
 * data wipe continue in the background; an unconfirmed hard delete is retried
 * automatically by the finish worker, never by the participant.
 */
interface OptOutRepository {
    suspend fun status(): OptOutStatus

    /** The same status, live: the terminal screen follows the hard delete as it resolves. */
    val statusUpdates: Flow<OptOutStatus>

    suspend fun optOutSoft(): OptOutResult
    suspend fun optOutHard(): OptOutResult
}
