package com.example.smartuse_frontend.ui.welcome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartuse_frontend.data.KeyPermission
import com.example.smartuse_frontend.data.PermissionChecker
import com.example.smartuse_frontend.data.SessionRepository
import com.example.smartuse_frontend.data.status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface WelcomeUiState {
    object Loading : WelcomeUiState
    data class Success(
        /** Non-null while an upcoming cutoff ends this participant's collection early. */
        val announcedCutoff: LocalDate?,
        val missingPermissions: Set<KeyPermission>,
    ) : WelcomeUiState
    data class Error(val message: String) : WelcomeUiState
}

class WelcomeViewModel(
    private val sessionRepository: SessionRepository,
    private val permissionChecker: PermissionChecker,
) : ViewModel() {
    var uiState by mutableStateOf<WelcomeUiState>(WelcomeUiState.Loading)
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            uiState = try {
                WelcomeUiState.Success(
                    announcedCutoff = sessionRepository.announcedCutoff(),
                    missingPermissions = permissionChecker.status().missing,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WelcomeUiState.Error(e.message ?: "Error desconocido")
            }
        }
    }
}
