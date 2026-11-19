package com.example.smartuse_frontend.ui.status

import com.example.smartuse_frontend.data.FakePermissionChecker
import com.example.smartuse_frontend.data.FakeSyncStatusRepository
import com.example.smartuse_frontend.data.PermissionStatus
import com.example.smartuse_frontend.jobs.FakeJobStateStore
import com.example.smartuse_frontend.jobs.JobFailure
import com.example.smartuse_frontend.jobs.JobHealth
import com.example.smartuse_frontend.jobs.JobName
import com.example.smartuse_frontend.jobs.JobState
import com.example.smartuse_frontend.network.FailureKind
import com.example.smartuse_frontend.util.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class StatusViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val lastSync = Instant.parse("2026-01-02T00:00:00Z")

    private fun viewModel(
        store: FakeJobStateStore = FakeJobStateStore(),
        permissions: FakePermissionChecker = FakePermissionChecker(usageAccessGranted = false),
    ) = StatusViewModel(
        permissionChecker = permissions,
        syncStatusRepository = FakeSyncStatusRepository(lastSync = lastSync),
        jobStateStore = store,
        stuckAfterFailedRuns = 3,
    )

    @Test
    fun `maps repository values and job health into a single success state`() = runTest {
        val captureAt = Instant.parse("2026-01-02T06:00:00Z")
        val store = FakeJobStateStore(mapOf(JobName.CAPTURE to JobState().afterSuccess(captureAt, 10)))

        val viewModel = viewModel(store)
        advanceUntilIdle()

        val state = viewModel.uiState as StatusUiState.Success
        assertEquals(
            PermissionStatus(usageAccess = false, notifications = true, batteryExemption = true),
            state.permissions,
        )
        assertEquals(lastSync, state.lastCanonicalSync)
        assertEquals(JobHealth(lastSuccessAt = captureAt, stuckSince = null), state.capture)
        assertEquals(JobHealth(null, null), state.sync)
    }

    @Test
    fun `a job with enough consecutive failed runs is reported stuck since its first failure`() = runTest {
        val failure = JobFailure(FailureKind.PERMANENT, "HTTP 409")
        val t1 = Instant.parse("2026-01-02T00:00:00Z")
        val t2 = Instant.parse("2026-01-02T06:00:00Z")
        val t3 = Instant.parse("2026-01-02T12:00:00Z")
        val stuck = JobState().afterFailedRun(t1, failure, null).afterFailedRun(t2, failure, null).afterFailedRun(t3, failure, null)
        val notYet = JobState().afterFailedRun(t1, failure, null).afterFailedRun(t2, failure, null)
        val store = FakeJobStateStore(mapOf(JobName.SYNC to stuck, JobName.CAPTURE to notYet))

        val viewModel = viewModel(store)
        advanceUntilIdle()

        val state = viewModel.uiState as StatusUiState.Success
        assertEquals(t1, state.sync.stuckSince)
        assertEquals(null, state.capture.stuckSince)
    }

    @Test
    fun `reloading picks up a permission restored in system settings`() = runTest {
        val permissions = FakePermissionChecker(usageAccessGranted = false)
        val viewModel = viewModel(permissions = permissions)
        advanceUntilIdle()
        assertFalse((viewModel.uiState as StatusUiState.Success).permissions.usageAccess)

        permissions.usageAccessGranted = true
        viewModel.load()
        advanceUntilIdle()

        val state = viewModel.uiState as StatusUiState.Success
        assertTrue(state.permissions.usageAccess)
        assertFalse(state.showLapseCard)
    }

    @Test
    fun `the lapse card shows while a permission is missing`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue((viewModel.uiState as StatusUiState.Success).showLapseCard)
    }
}
