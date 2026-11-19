package com.example.smartuse_frontend.ui.dataoverview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartuse_frontend.data.CredentialStore
import com.example.smartuse_frontend.data.EpochRecord
import com.example.smartuse_frontend.data.ParticipantProfile
import com.example.smartuse_frontend.data.ProfileRepository
import com.example.smartuse_frontend.data.UsageStatsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

sealed interface DataOverviewUiState {
    object Loading : DataOverviewUiState
    data class Success(
        val profile: ParticipantProfile,
        val userId: String?,
        val signupDate: LocalDate?,
        val selectedDate: LocalDate,
        val epochs: List<EpochRecord>,
    ) : DataOverviewUiState
    data class Error(val message: String) : DataOverviewUiState
}

class DataOverviewViewModel(
    private val usageStatsRepository: UsageStatsRepository,
    private val profileRepository: ProfileRepository,
    private val credentialStore: CredentialStore,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zoneId) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    var uiState by mutableStateOf<DataOverviewUiState>(DataOverviewUiState.Loading)
        private set

    init {
        viewModelScope.launch {
            load(today())
        }
    }

    fun onDateSelected(date: LocalDate) {
        viewModelScope.launch { load(date) }
    }

    // Re-reads the local canonical DB for the day already on screen (today if the
    // page hasn't loaded successfully). It does not run capture — that is the
    // scheduled job's work — so it only shows epochs that job has already written.
    fun refresh() {
        val date = (uiState as? DataOverviewUiState.Success)?.selectedDate ?: today()
        viewModelScope.launch { load(date) }
    }

    private suspend fun load(date: LocalDate) {
        uiState = try {
            val profile = profileRepository.getProfile()
            val userId = credentialStore.userId()
            val signupDate = credentialStore.signupTimestamp()?.atZone(zoneId)?.toLocalDate()
            val epochs = withContext(ioDispatcher) {
                usageStatsRepository.getEpochsOverlappingDay(date, zoneId)
            }
            DataOverviewUiState.Success(
                profile = profile,
                userId = userId,
                signupDate = signupDate,
                selectedDate = date,
                epochs = epochs,
            )
        } catch (e: Exception) {
            DataOverviewUiState.Error(e.message ?: "Error desconocido")
        }
    }
}
