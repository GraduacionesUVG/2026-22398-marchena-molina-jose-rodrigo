package com.example.smartuse_frontend.ui.dataoverview

import com.example.smartuse_frontend.config.ExperimentGroup
import com.example.smartuse_frontend.data.EpochRecord
import com.example.smartuse_frontend.data.FakeCredentialStore
import com.example.smartuse_frontend.data.FakeProfileRepository
import com.example.smartuse_frontend.data.FakeUsageStatsRepository
import com.example.smartuse_frontend.data.ParticipantProfile
import com.example.smartuse_frontend.data.PersistedSession
import com.example.smartuse_frontend.util.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DataOverviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `exposes profile, signup date, and the selected day's epochs`() = runTest {
        val profile = ParticipantProfile(age = 22, gender = "male", universityMajor = "Physics", sasSvScore = 40)
        val signup = Instant.parse("2026-01-01T00:00:00Z")
        val today = LocalDate.of(2026, 1, 3)
        val epochsForToday = listOf(EpochRecord(startTs = 0L, endTs = 1000L, isUse = true, distinctAppCount = 2))

        val credentialStore = FakeCredentialStore(
            initial = PersistedSession(
                userId = "user-1",
                authToken = "token",
                group = ExperimentGroup.CONTROL,
                signupTs = signup,
                profile = profile,
            ),
        )

        val viewModel = DataOverviewViewModel(
            usageStatsRepository = FakeUsageStatsRepository(mapOf(today to epochsForToday)),
            profileRepository = FakeProfileRepository(profile = profile),
            credentialStore = credentialStore,
            zoneId = ZoneOffset.UTC,
            today = { today },
            ioDispatcher = mainDispatcherRule.dispatcher,
        )

        val state = viewModel.uiState as DataOverviewUiState.Success
        assertEquals(profile, state.profile)
        assertEquals("user-1", state.userId)
        assertEquals(LocalDate.of(2026, 1, 1), state.signupDate)
        assertEquals(today, state.selectedDate)
        assertEquals(epochsForToday, state.epochs)
    }

    @Test
    fun `onDateSelected reloads epochs for the newly selected day`() = runTest {
        val profile = ParticipantProfile(age = 22, gender = "male", universityMajor = "Physics", sasSvScore = 40)
        val today = LocalDate.of(2026, 1, 3)
        val yesterday = today.minusDays(1)
        val epochsForYesterday = listOf(EpochRecord(startTs = 0L, endTs = 500L, isUse = false, distinctAppCount = 0))

        val viewModel = DataOverviewViewModel(
            usageStatsRepository = FakeUsageStatsRepository(mapOf(yesterday to epochsForYesterday)),
            profileRepository = FakeProfileRepository(profile = profile),
            credentialStore = FakeCredentialStore(),
            zoneId = ZoneOffset.UTC,
            today = { today },
            ioDispatcher = mainDispatcherRule.dispatcher,
        )

        viewModel.onDateSelected(yesterday)

        val state = viewModel.uiState as DataOverviewUiState.Success
        assertEquals(yesterday, state.selectedDate)
        assertEquals(epochsForYesterday, state.epochs)
    }

    @Test
    fun `refresh reloads the selected day and keeps the selection`() = runTest {
        val profile = ParticipantProfile(age = 22, gender = "male", universityMajor = "Physics", sasSvScore = 40)
        val today = LocalDate.of(2026, 1, 3)
        val yesterday = today.minusDays(1)
        val repository = FakeUsageStatsRepository()

        val viewModel = DataOverviewViewModel(
            usageStatsRepository = repository,
            profileRepository = FakeProfileRepository(profile = profile),
            credentialStore = FakeCredentialStore(),
            zoneId = ZoneOffset.UTC,
            today = { today },
            ioDispatcher = mainDispatcherRule.dispatcher,
        )
        viewModel.onDateSelected(yesterday)
        assertEquals(emptyList<EpochRecord>(), (viewModel.uiState as DataOverviewUiState.Success).epochs)

        // The capture pipeline closes another epoch after the page first loaded.
        val newEpochs = listOf(EpochRecord(startTs = 0L, endTs = 500L, isUse = true, distinctAppCount = 3))
        repository.epochsByDay = mapOf(yesterday to newEpochs)
        viewModel.refresh()

        val state = viewModel.uiState as DataOverviewUiState.Success
        assertEquals(yesterday, state.selectedDate)
        assertEquals(newEpochs, state.epochs)
    }
}
