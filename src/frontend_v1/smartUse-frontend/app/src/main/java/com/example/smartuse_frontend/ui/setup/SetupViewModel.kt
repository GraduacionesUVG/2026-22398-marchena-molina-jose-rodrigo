package com.example.smartuse_frontend.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartuse_frontend.config.ExperimentGroup
import com.example.smartuse_frontend.data.CredentialStore
import com.example.smartuse_frontend.data.KeystoreKeyManager
import com.example.smartuse_frontend.data.ParticipantProfile
import com.example.smartuse_frontend.data.PermissionChecker
import com.example.smartuse_frontend.data.PersistedSession
import com.example.smartuse_frontend.data.RegisterOutcome
import com.example.smartuse_frontend.data.RegistrationRepository
import com.example.smartuse_frontend.data.SetupDraft
import com.example.smartuse_frontend.data.SetupStateRepository
import com.example.smartuse_frontend.data.status
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.StudyNotifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val SERVER_UNREACHABLE = "No se pudo conectar con el servidor. Intente de nuevo."

class SetupViewModel(
    private val registrationRepository: RegistrationRepository,
    private val credentialStore: CredentialStore,
    private val keystoreKeyManager: KeystoreKeyManager,
    private val setupStateRepository: SetupStateRepository,
    private val permissionChecker: PermissionChecker,
    private val studyNotifier: StudyNotifier,
    private val announcedCutoff: suspend () -> LocalDate? = { null },
    val needsAutostartStep: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    var step by mutableStateOf<SetupStep>(SetupStep.Terms)
        private set
    var draft by mutableStateOf(SetupDraft())
        private set
    var inFlight by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            // Resume: if POST /users already succeeded, never call it again —
            // jump to key generation and finish.
            if (credentialStore.hasCredentials()) {
                step = SetupStep.GeneratingKey
                generateKeyAndFinish()
            }
        }
    }

    fun acceptTerms() {
        step = SetupStep.ProfileForm
    }

    fun submitProfile(draft: SetupDraft) {
        this.draft = draft
        step = when (draft.resolvedGroup) {
            ExperimentGroup.CONTROL -> SetupStep.UsageAccess
            null -> SetupStep.ProfileForm // UI validates the field is chosen; defensive
            else -> SetupStep.Unsupported
        }
    }

    fun onUsageAccessContinue() {
        if (!permissionChecker.hasUsageAccess()) return
        step = SetupStep.NotificationAccess
    }

    fun onNotificationAccessContinue() {
        if (step != SetupStep.NotificationAccess) return
        if (!permissionChecker.hasNotificationPermission()) return
        step = SetupStep.BatteryOptimization
    }

    fun onBatteryOptimizationContinue() {
        if (step != SetupStep.BatteryOptimization) return
        if (!permissionChecker.hasBatteryOptimizationExemption()) return
        if (needsAutostartStep) step = SetupStep.Autostart else runRegistration()
    }

    // Always allowed: Android can't tell whether autostart was switched on.
    fun onAutostartContinue() {
        if (step != SetupStep.Autostart) return
        runRegistration()
    }

    fun retryRegistration() = runRegistration()

    fun backToForm() {
        step = SetupStep.ProfileForm
    }

    fun retryKeyGeneration() {
        step = SetupStep.GeneratingKey
        viewModelScope.launch { generateKeyAndFinish() }
    }

    private fun runRegistration() {
        step = SetupStep.Registering
        inFlight = true
        viewModelScope.launch {
            when (val outcome = registrationRepository.register(draft)) {
                is RegisterOutcome.Registered -> {
                    credentialStore.saveSession(
                        PersistedSession(
                            userId = outcome.userId,
                            authToken = outcome.authToken,
                            group = draft.resolvedGroup ?: ExperimentGroup.CONTROL,
                            signupTs = outcome.signupTs,
                            profile = ParticipantProfile(
                                age = draft.age,
                                gender = draft.gender,
                                universityMajor = draft.universityMajor,
                                sasSvScore = draft.sasSvScore,
                            ),
                        ),
                    )
                    inFlight = false
                    step = SetupStep.GeneratingKey
                    generateKeyAndFinish()
                }

                is RegisterOutcome.Rejected -> {
                    inFlight = false
                    step = SetupStep.RegisterError(outcome.message)
                }

                RegisterOutcome.Retryable -> {
                    inFlight = false
                    step = SetupStep.RegisterError(SERVER_UNREACHABLE)
                }
            }
        }
    }

    private suspend fun generateKeyAndFinish() {
        inFlight = true
        val ok = runCatching {
            withContext(ioDispatcher) { keystoreKeyManager.ensureHmacKey() }
        }.isSuccess
        inFlight = false
        if (ok) {
            setupStateRepository.markComplete()
            // The first capture run is up to 6h away; show "recording" now.
            val cutoff = withContext(ioDispatcher) { announcedCutoff() }
            studyNotifier.show(StudyNotice.Recording(permissionChecker.status().missing, cutoff))
            step = SetupStep.Done
        } else {
            step = SetupStep.KeystoreError
        }
    }
}
