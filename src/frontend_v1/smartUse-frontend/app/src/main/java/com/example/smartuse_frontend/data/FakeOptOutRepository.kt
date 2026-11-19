package com.example.smartuse_frontend.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeOptOutRepository(
    private val shouldSucceed: Boolean = true,
) : OptOutRepository {
    private val currentStatus = MutableStateFlow<OptOutStatus>(OptOutStatus.NotOptedOut)

    override val statusUpdates: Flow<OptOutStatus> = currentStatus

    override suspend fun status(): OptOutStatus = currentStatus.value

    override suspend fun optOutSoft(): OptOutResult {
        delay(200)
        currentStatus.value = OptOutStatus.SoftDone
        return OptOutResult.Success
    }

    override suspend fun optOutHard(): OptOutResult {
        delay(200)
        currentStatus.value = OptOutStatus.Hard(deleteConfirmed = shouldSucceed, attemptFailed = !shouldSucceed)
        return if (shouldSucceed) {
            OptOutResult.Success
        } else {
            OptOutResult.Failure("No se pudo conectar con el servidor.")
        }
    }
}
