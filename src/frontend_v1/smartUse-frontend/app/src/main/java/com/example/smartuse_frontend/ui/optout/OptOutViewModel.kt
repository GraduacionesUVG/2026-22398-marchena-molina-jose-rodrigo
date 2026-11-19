package com.example.smartuse_frontend.ui.optout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartuse_frontend.data.OptOutRepository
import kotlinx.coroutines.launch

enum class OptOutKind { SOFT, HARD }

/**
 * Only responsible for kicking off the chosen flow. Both real methods stop
 * capture/sync locally before returning (see RealOptOutRepository); from
 * there, MainActivity's `hasOptedOut` observation takes over and swaps this
 * whole screen out for OptOutDoneScreen, so there is nothing further to
 * track here.
 */
class OptOutViewModel(private val optOutRepository: OptOutRepository) : ViewModel() {
    /** True while an opt-out call is running; the screen disables both buttons. */
    var inFlight by mutableStateOf(false)
        private set

    // Only one opt-out at a time: a second tap (either kind) while one runs is ignored.
    fun optOut(kind: OptOutKind) {
        if (inFlight) return
        inFlight = true
        viewModelScope.launch {
            try {
                when (kind) {
                    OptOutKind.SOFT -> optOutRepository.optOutSoft()
                    OptOutKind.HARD -> optOutRepository.optOutHard()
                }
            } finally {
                inFlight = false
            }
        }
    }
}
