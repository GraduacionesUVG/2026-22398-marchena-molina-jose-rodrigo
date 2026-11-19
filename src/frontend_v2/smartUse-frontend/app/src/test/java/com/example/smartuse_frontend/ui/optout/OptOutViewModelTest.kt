package com.example.smartuse_frontend.ui.optout

import com.example.smartuse_frontend.data.OptOutRepository
import com.example.smartuse_frontend.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class OptOutViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private class GatedRepository : OptOutRepository {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        override suspend fun optOut() {
            calls++
            gate.await()
        }
    }

    @Test
    fun `a second tap while one opt-out runs is ignored`() = runTest {
        val repo = GatedRepository()
        val vm = OptOutViewModel(repo)

        vm.optOut()
        vm.optOut()
        advanceUntilIdle()
        assertEquals(1, repo.calls)

        repo.gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.inFlight)
    }
}
