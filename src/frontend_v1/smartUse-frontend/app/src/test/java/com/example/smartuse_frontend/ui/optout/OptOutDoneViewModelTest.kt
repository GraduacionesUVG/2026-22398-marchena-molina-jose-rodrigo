package com.example.smartuse_frontend.ui.optout

import com.example.smartuse_frontend.data.OptOutRepository
import com.example.smartuse_frontend.data.OptOutResult
import com.example.smartuse_frontend.data.OptOutStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class StatusStreamRepository(initial: OptOutStatus) : OptOutRepository {
    val stream = MutableStateFlow(initial)
    override val statusUpdates = stream
    override suspend fun status() = stream.value
    override suspend fun optOutSoft() = OptOutResult.Success
    override suspend fun optOutHard() = OptOutResult.Success
}

class OptOutDoneViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // The screen opens while the delete request is still in flight:
    // it must move on by itself once the backend confirms.
    @Test
    fun `the screen follows a hard delete from pending to confirmed`() = runTest {
        val repository = StatusStreamRepository(OptOutStatus.Hard(deleteConfirmed = false, attemptFailed = false))
        val viewModel = OptOutDoneViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        val pending = viewModel.ui.value
        assertEquals(PENDING_LINE, pending.statusLine)
        assertTrue(pending.inProgress)
        assertFalse(pending.canUninstall)
        assertEquals(UNINSTALL_HINT, pending.uninstallHint)

        repository.stream.value = OptOutStatus.Hard(deleteConfirmed = true, attemptFailed = false)
        dispatcher.scheduler.advanceUntilIdle()

        val confirmed = viewModel.ui.value
        assertEquals(CONFIRMED_LINE, confirmed.statusLine)
        assertFalse(confirmed.inProgress)
        assertTrue(confirmed.canUninstall)
        assertNull(confirmed.uninstallHint)
    }

    // Retries are automatic (the finish worker); the screen only says so.
    @Test
    fun `a failed attempt keeps retrying automatically and uninstall stays disabled`() = runTest {
        val viewModel = OptOutDoneViewModel(StatusStreamRepository(OptOutStatus.Hard(deleteConfirmed = false, attemptFailed = true)))
        dispatcher.scheduler.advanceUntilIdle()

        val ui = viewModel.ui.value
        assertEquals(RETRYING_LINE, ui.statusLine)
        assertTrue(ui.inProgress)
        assertFalse(ui.canUninstall)
    }

    @Test
    fun `a soft opt-out shows no deletion status and can uninstall`() = runTest {
        val viewModel = OptOutDoneViewModel(StatusStreamRepository(OptOutStatus.SoftDone))
        dispatcher.scheduler.advanceUntilIdle()

        val ui = viewModel.ui.value
        assertNull(ui.statusLine)
        assertFalse(ui.inProgress)
        assertTrue(ui.canUninstall)
        assertNull(ui.uninstallHint)
    }
}
