package com.example.smartuse_frontend.ui.optout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartuse_frontend.data.OptOutRepository
import com.example.smartuse_frontend.data.OptOutStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal const val PENDING_LINE = "Eliminando tus datos… Reintentaremos automáticamente si hay un problema."
internal const val RETRYING_LINE = "Aún no se pudo confirmar la eliminación. Reintentando automáticamente."
internal const val CONFIRMED_LINE = "Tus datos ya fueron eliminados de nuestros servidores."
internal const val UNINSTALL_HINT =
    "Espera a que confirmemos la eliminación antes de desinstalar. " +
        "Si desinstalas ahora, tus datos podrían quedar en nuestros servidores."

/**
 * What the terminal screen shows. Uninstalling before a hard delete is confirmed
 * would lose it for good (the finish worker and the token go with the app), so
 * the button waits for the confirmation; Android's own settings can still
 * uninstall, the disabled button is guidance.
 */
data class OptOutDoneUi(
    val statusLine: String?,
    val inProgress: Boolean,
    val canUninstall: Boolean,
    val uninstallHint: String?,
)

internal fun optOutDoneUi(status: OptOutStatus?): OptOutDoneUi = when {
    status == null -> OptOutDoneUi(statusLine = null, inProgress = false, canUninstall = false, uninstallHint = null)
    status !is OptOutStatus.Hard -> OptOutDoneUi(statusLine = null, inProgress = false, canUninstall = true, uninstallHint = null)
    status.deleteConfirmed -> OptOutDoneUi(CONFIRMED_LINE, inProgress = false, canUninstall = true, uninstallHint = null)
    else -> OptOutDoneUi(
        statusLine = if (status.attemptFailed) RETRYING_LINE else PENDING_LINE,
        inProgress = true,
        canUninstall = false,
        uninstallHint = UNINSTALL_HINT,
    )
}

/**
 * Follows the opt-out status live: the screen opens while the hard delete is
 * still in flight and moves on by itself when it resolves. Retries are the
 * finish worker's, never the participant's.
 */
class OptOutDoneViewModel(repository: OptOutRepository) : ViewModel() {
    val ui: StateFlow<OptOutDoneUi> = repository.statusUpdates
        .map { optOutDoneUi(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, optOutDoneUi(null))
}
