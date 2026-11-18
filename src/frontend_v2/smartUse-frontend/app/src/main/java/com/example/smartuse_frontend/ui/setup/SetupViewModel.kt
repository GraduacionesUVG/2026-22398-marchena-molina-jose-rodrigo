package com.example.smartuse_frontend.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val SERVER_UNREACHABLE = "No se pudo conectar con el servidor. Intente de nuevo."

/** How long setup waits for the profile built from the earlier history; past it, the cycle finishes the job. */
private val PROFILE_BUILD_LIMIT: Duration = Duration.ofSeconds(60)

class SetupViewModel(
    private val registrationRepository: RegistrationRepository,
    private val credentialStore: CredentialStore,
    private val keystoreKeyManager: KeystoreKeyManager,
    private val setupStateRepository: SetupStateRepository,
    private val permissionChecker: PermissionChecker,
    private val studyNotifier: StudyNotifier,
    private val announcedCutoff: suspend () -> LocalDate? = { null },
    val needsAutostartStep: Boolean = false,
    private val earlierUsageCheck: suspend (start: Instant, end: Instant) -> Boolean = { _, _ -> false },
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    // Capture, then the profile freeze: run once at the end of setup when the earlier history was accepted.
    private val buildProfile: suspend () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    var step by mutableStateOf<SetupStep>(SetupStep.Terms)
        private set
    var draft by mutableStateOf(SetupDraft())
        private set
    var inFlight by mutableStateOf(false)
        private set

    // The earlier-usage choice, made once per wizard: back-to-form and retries don't re-ask.
    private var earlierUsageAnswered = false
    private var pendingHistoryStart: Instant? = null

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
        step = SetupStep.UsageAccess
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
        step = SetupStep.OverlayAccess
    }

    fun onOverlayAccessContinue() {
        if (step != SetupStep.OverlayAccess) return
        if (!permissionChecker.canDrawOverlays()) return
        if (needsAutostartStep) step = SetupStep.Autostart else afterPermissions()
    }

    // Always allowed: Android can't tell whether autostart was switched on.
    fun onAutostartContinue() {
        if (step != SetupStep.Autostart) return
        afterPermissions()
    }

    fun acceptEarlierUsage() = answerEarlierUsage(pendingHistoryStart)

    fun rejectEarlierUsage() = answerEarlierUsage(null)

    private fun answerEarlierUsage(start: Instant?) {
        if (step != SetupStep.EarlierUsage || inFlight) return
        inFlight = true
        viewModelScope.launch {
            setupStateRepository.setHistoryStart(start)
            earlierUsageAnswered = true
            inFlight = false
            runRegistration()
        }
    }

    // The consent step when the phone has usage in the window; skipped (nothing
    // stored) when it has none or the check fails, so setup never depends on it.
    private fun afterPermissions() {
        if (earlierUsageAnswered) return runRegistration()
        if (inFlight) return // a second tap while the check runs
        inFlight = true
        viewModelScope.launch {
            val at = now()
            val start = historyStart(at, zone())
            val end = windowEnd(at, zone())
            val has = runCatching { withContext(ioDispatcher) { earlierUsageCheck(start, end) } }.getOrDefault(false)
            inFlight = false
            if (has) {
                pendingHistoryStart = start
                step = SetupStep.EarlierUsage
            } else {
                setupStateRepository.setHistoryStart(null)
                earlierUsageAnswered = true
                runRegistration()
            }
        }
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
            // With the earlier history, the profile can freeze now: build it before the
            // dashboard opens, so its start box is already right (frozen, or the date).
            if (setupStateRepository.historyStart() != null) {
                step = SetupStep.BuildingProfile
                buildProfileWithinLimit()
            }
            setupStateRepository.markComplete()
            // The first cycle may be up to t away; show "recording" now.
            val cutoff = withContext(ioDispatcher) { announcedCutoff() }
            studyNotifier.show(StudyNotice.Recording(permissionChecker.status().missing, cutoff))
            step = SetupStep.Done
        } else {
            step = SetupStep.KeystoreError
        }
    }

    // Never blocks setup: a failure or a slow capture (blocking IO, so awaited, not
    // cancelled) leaves the freeze to the next cycle.
    private suspend fun buildProfileWithinLimit() {
        val build = viewModelScope.async(ioDispatcher) { runCatching { buildProfile() } }
        withTimeoutOrNull(PROFILE_BUILD_LIMIT.toMillis()) { build.await() }
    }
}
