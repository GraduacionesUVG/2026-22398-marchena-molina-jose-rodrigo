package com.example.smartuse_frontend.ui.welcome

import com.example.smartuse_frontend.data.FakePermissionChecker
import com.example.smartuse_frontend.data.FakeSessionRepository
import com.example.smartuse_frontend.data.KeyPermission
import com.example.smartuse_frontend.util.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class WelcomeViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val signup = Instant.parse("2026-01-01T00:00:00Z")

    private fun viewModel(
        daysSinceSignup: Long = 5,
        cutoff: LocalDate? = null,
        permissions: FakePermissionChecker = FakePermissionChecker(),
    ) = WelcomeViewModel(
        sessionRepository = FakeSessionRepository(
            signupInstant = signup,
            clock = Clock.fixed(signup.plus(daysSinceSignup, ChronoUnit.DAYS), ZoneOffset.UTC),
            cutoff = cutoff,
        ),
        permissionChecker = permissions,
    ).also { mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle() }

    private fun WelcomeViewModel.success() = uiState as WelcomeUiState.Success

    @Test
    fun `announces a cutoff that ends collection before day 21`() = runTest {
        assertEquals(LocalDate.of(2026, 1, 10), viewModel(cutoff = LocalDate.of(2026, 1, 10)).success().announcedCutoff)
    }

    @Test
    fun `announces nothing without a cutoff`() = runTest {
        assertNull(viewModel().success().announcedCutoff)
    }

    @Test
    fun `announces nothing for a cutoff after this participant's day 21`() = runTest {
        assertNull(viewModel(cutoff = LocalDate.of(2026, 2, 1)).success().announcedCutoff)
    }

    @Test
    fun `reports no missing permissions when everything is granted`() = runTest {
        assertEquals(emptySet<KeyPermission>(), viewModel().success().missingPermissions)
    }

    @Test
    fun `reports the missing permissions`() = runTest {
        val permissions = FakePermissionChecker(usageAccessGranted = false, notificationsGranted = false)

        assertEquals(
            setOf(KeyPermission.USAGE_ACCESS, KeyPermission.NOTIFICATIONS),
            viewModel(permissions = permissions).success().missingPermissions,
        )
    }

    @Test
    fun `load picks up a permission restored since the page was first shown`() = runTest {
        val permissions = FakePermissionChecker(batteryExemptionGranted = false)
        val viewModel = viewModel(permissions = permissions)
        assertEquals(setOf(KeyPermission.BATTERY_EXEMPTION), viewModel.success().missingPermissions)

        permissions.batteryExemptionGranted = true
        viewModel.load()
        advanceUntilIdle()

        assertEquals(emptySet<KeyPermission>(), viewModel.success().missingPermissions)
    }
}
