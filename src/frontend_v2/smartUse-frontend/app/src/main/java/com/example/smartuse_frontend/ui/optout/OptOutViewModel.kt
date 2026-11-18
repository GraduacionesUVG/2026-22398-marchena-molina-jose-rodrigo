package com.example.smartuse_frontend.ui.optout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartuse_frontend.data.OptOutRepository
import kotlinx.coroutines.launch

/**
 * Only kicks off the opt-out; MainActivity's ending state then swaps the
 * whole app for the end forms.
 */
class OptOutViewModel(private val optOutRepository: OptOutRepository) : ViewModel() {
    /** True while the opt-out runs; the screen disables the button. */
    var inFlight by mutableStateOf(false)
        private set

    fun optOut() {
        if (inFlight) return
        inFlight = true
        viewModelScope.launch {
            try {
                optOutRepository.optOut()
            } finally {
                inFlight = false
            }
        }
    }
}
