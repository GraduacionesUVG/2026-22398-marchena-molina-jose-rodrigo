package com.example.smartuse_frontend.ui.optout

import org.junit.Assert.assertFalse
import com.example.smartuse_frontend.data.OptOutResult
import com.example.smartuse_frontend.data.OptOutRepository
import com.example.smartuse_frontend.data.FakeOptOutRepository
import com.example.smartuse_frontend.data.OptOutStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OptOutViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `soft opt-out calls through to the repository`() = runTest {
        val repository = FakeOptOutRepository()
        val viewModel = OptOutViewModel(repository)

        viewModel.optOut(OptOutKind.SOFT)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(OptOutStatus.SoftDone, repository.status())
    }

    @Test
    fun `hard opt-out calls through to the repository`() = runTest {
        val repository = FakeOptOutRepository(shouldSucceed = true)
        val viewModel = OptOutViewModel(repository)

        viewModel.optOut(OptOutKind.HARD)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue((repository.status() as OptOutStatus.Hard).deleteConfirmed)
    }

    @Test
    fun `a second confirm while one is running is ignored, and the screen knows it is busy`() = runTest {
        var calls = 0
        val repository = object : OptOutRepository by FakeOptOutRepository() {
            override suspend fun optOutHard(): OptOutResult {
                calls++
                kotlinx.coroutines.delay(5_000)
                return OptOutResult.Success
            }
        }
        val viewModel = OptOutViewModel(repository)

        viewModel.optOut(OptOutKind.HARD)
        dispatcher.scheduler.runCurrent()
        assertTrue(viewModel.inFlight)
        viewModel.optOut(OptOutKind.HARD)
        viewModel.optOut(OptOutKind.SOFT)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, calls)
        assertFalse(viewModel.inFlight)
    }
}
