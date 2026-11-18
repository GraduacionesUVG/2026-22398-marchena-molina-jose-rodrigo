package com.example.smartuse_frontend.ui.status

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartuse_frontend.data.PermissionChecker
import com.example.smartuse_frontend.data.PermissionStatus
import com.example.smartuse_frontend.data.status
import com.example.smartuse_frontend.jobs.JobHealth
import com.example.smartuse_frontend.jobs.JobName
import com.example.smartuse_frontend.jobs.JobStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed interface StatusUiState {
    object Loading : StatusUiState
    data class Success(
        val permissions: PermissionStatus,
        val capture: JobHealth,
    ) : StatusUiState {
        /** The "permissions were removed" prompt. */
        val showLapseCard: Boolean get() = !permissions.isComplete
    }
    data class Error(val message: String) : StatusUiState
}

class StatusViewModel(
    private val permissionChecker: PermissionChecker,
    private val jobStateStore: JobStateStore,
    private val stuckAfterFailedRuns: Int,
) : ViewModel() {
    var uiState by mutableStateOf<StatusUiState>(StatusUiState.Loading)
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            uiState = try {
                StatusUiState.Success(
                    permissions = permissionChecker.status(),
                    capture = jobStateStore.get(JobName.CAPTURE).toHealth(stuckAfterFailedRuns),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StatusUiState.Error(e.message ?: "Error desconocido")
            }
        }
    }
}
