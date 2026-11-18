package com.example.smartuse_frontend.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

class FakeSetupStateRepository(initial: Boolean = false) : SetupStateRepository {
    private val state = MutableStateFlow(initial)
    override val isComplete: StateFlow<Boolean> = state
    override suspend fun markComplete() { state.value = true }
    override suspend fun isCompleteNow(): Boolean = state.value

    var historyStartValue: Instant? = null
    override suspend fun historyStart(): Instant? = historyStartValue
    override suspend fun setHistoryStart(start: Instant?) { historyStartValue = start }
}
