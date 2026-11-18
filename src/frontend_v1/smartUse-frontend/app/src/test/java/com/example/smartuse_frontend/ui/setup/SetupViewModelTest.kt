package com.example.smartuse_frontend.ui.setup

import com.example.smartuse_frontend.config.ExperimentGroup
import com.example.smartuse_frontend.data.FakeCredentialStore
import com.example.smartuse_frontend.data.FakeKeystoreKeyManager
import com.example.smartuse_frontend.data.FakePermissionChecker
import com.example.smartuse_frontend.data.FakeRegistrationRepository
import com.example.smartuse_frontend.data.FakeSetupStateRepository
import com.example.smartuse_frontend.data.ParticipantProfile
import com.example.smartuse_frontend.data.PersistedSession
import com.example.smartuse_frontend.data.RegisterOutcome
import com.example.smartuse_frontend.data.SetupDraft
import com.example.smartuse_frontend.notification.FakeStudyNotifier
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.util.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class SetupViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val controlDraft = SetupDraft(
        age = 21, gender = "Femenino", universityMajor = "Psicología",
        sasSvScore = 34, monthOption = "Julio",
    )

    private fun viewModel(
        registration: FakeRegistrationRepository = FakeRegistrationRepository(),
        credentials: FakeCredentialStore = FakeCredentialStore(),
        keystore: FakeKeystoreKeyManager = FakeKeystoreKeyManager(),
        setupState: FakeSetupStateRepository = FakeSetupStateRepository(),
        permissions: FakePermissionChecker = FakePermissionChecker(
            usageAccessGranted = true,
            notificationsGranted = true,
            batteryExemptionGranted = true,
        ),
        notifier: FakeStudyNotifier = FakeStudyNotifier(),
        announcedCutoff: suspend () -> LocalDate? = { null },
        needsAutostartStep: Boolean = false,
    ) = SetupViewModel(
        registration, credentials, keystore, setupState, permissions,
        studyNotifier = notifier,
        announcedCutoff = announcedCutoff,
        needsAutostartStep = needsAutostartStep,
        ioDispatcher = mainDispatcherRule.dispatcher,
    )

    // All three permission steps, in order, with everything granted — the run-up to registration.
    private fun SetupViewModel.passPermissionSteps() {
        onUsageAccessContinue()
        onNotificationAccessContinue()
        onBatteryOptimizationContinue()
    }

    @Test
    fun `happy path walks every step and persists exactly once`() = runTest {
        val registration = FakeRegistrationRepository()
        val credentials = FakeCredentialStore()
        val keystore = FakeKeystoreKeyManager()
        val setupState = FakeSetupStateRepository()
        val vm = viewModel(registration, credentials, keystore, setupState)
        advanceUntilIdle()

        assertEquals(SetupStep.Terms, vm.step)
        vm.acceptTerms()
        assertEquals(SetupStep.ProfileForm, vm.step)

        vm.submitProfile(controlDraft)
        assertEquals(SetupStep.UsageAccess, vm.step)

        vm.onUsageAccessContinue()
        assertEquals(SetupStep.NotificationAccess, vm.step)
        assertEquals(0, registration.calls)

        vm.onNotificationAccessContinue()
        assertEquals(SetupStep.BatteryOptimization, vm.step)
        assertEquals(0, registration.calls)

        vm.onBatteryOptimizationContinue()
        advanceUntilIdle()

        assertEquals(SetupStep.Done, vm.step)
        assertEquals(1, registration.calls)
        assertEquals(1, credentials.saveCount)
        assertEquals(ExperimentGroup.CONTROL, credentials.experimentGroup())
        assertTrue(keystore.generated)
        assertTrue(setupState.isCompleteNow())
    }

    @Test
    fun `non-control month routes to Unsupported without registering`() = runTest {
        val registration = FakeRegistrationRepository()
        val vm = viewModel(registration = registration)
        advanceUntilIdle()
        vm.acceptTerms()

        vm.submitProfile(controlDraft.copy(monthOption = "Agosto"))

        assertEquals(SetupStep.Unsupported, vm.step)
        assertEquals(0, registration.calls)
    }

    @Test
    fun `usage access gate blocks the next step until granted`() = runTest {
        val registration = FakeRegistrationRepository()
        val permissions = FakePermissionChecker(usageAccessGranted = false, notificationsGranted = true)
        val vm = viewModel(registration = registration, permissions = permissions)
        advanceUntilIdle()
        vm.acceptTerms()
        vm.submitProfile(controlDraft)

        vm.onUsageAccessContinue()
        advanceUntilIdle()
        assertEquals(SetupStep.UsageAccess, vm.step)
        assertEquals(0, registration.calls)

        permissions.usageAccessGranted = true
        vm.onUsageAccessContinue()
        advanceUntilIdle()
        assertEquals(SetupStep.NotificationAccess, vm.step)
        assertEquals(0, registration.calls)
    }

    @Test
    fun `notification gate blocks the next step until granted`() = runTest {
        val registration = FakeRegistrationRepository()
        val permissions = FakePermissionChecker(usageAccessGranted = true, notificationsGranted = false)
        val vm = viewModel(registration = registration, permissions = permissions)
        advanceUntilIdle()
        vm.acceptTerms()
        vm.submitProfile(controlDraft)
        vm.onUsageAccessContinue()

        vm.onNotificationAccessContinue()
        advanceUntilIdle()
        assertEquals(SetupStep.NotificationAccess, vm.step)
        assertEquals(0, registration.calls)

        permissions.notificationsGranted = true
        vm.onNotificationAccessContinue()
        advanceUntilIdle()
        assertEquals(SetupStep.BatteryOptimization, vm.step)
        assertEquals(0, registration.calls)
    }

    @Test
    fun `battery exemption gate blocks registration until granted`() = runTest {
        val registration = FakeRegistrationRepository()
        val permissions = FakePermissionChecker(
            usageAccessGranted = true,
            notificationsGranted = true,
            batteryExemptionGranted = false,
        )
        val vm = viewModel(registration = registration, permissions = permissions)
        advanceUntilIdle()
        vm.acceptTerms()
        vm.submitProfile(controlDraft)
        vm.onUsageAccessContinue()
        vm.onNotificationAccessContinue()

        vm.onBatteryOptimizationContinue()
        advanceUntilIdle()
        assertEquals(SetupStep.BatteryOptimization, vm.step)
        assertEquals(0, registration.calls)

        permissions.batteryExemptionGranted = true
        vm.onBatteryOptimizationContinue()
        advanceUntilIdle()
        assertEquals(SetupStep.Done, vm.step)
        assertEquals(1, registration.calls)
    }

    @Test
    fun `notification continue is ignored outside its own step`() = runTest {
        val registration = FakeRegistrationRepository()
        val vm = viewModel(registration = registration)
        advanceUntilIdle()
        vm.acceptTerms()
        vm.submitProfile(controlDraft)

        vm.onNotificationAccessContinue() // still on UsageAccess
        advanceUntilIdle()

        assertEquals(SetupStep.UsageAccess, vm.step)
        assertEquals(0, registration.calls)
    }

    @Test
    fun `battery continue is ignored outside its own step`() = runTest {
        val registration = FakeRegistrationRepository()
        val vm = viewModel(registration = registration)
        advanceUntilIdle()
        vm.acceptTerms()
        vm.submitProfile(controlDraft)
        vm.onUsageAccessContinue()

        vm.onBatteryOptimizationContinue() // still on NotificationAccess
        advanceUntilIdle()

        assertEquals(SetupStep.NotificationAccess, vm.step)
        assertEquals(0, registration.calls)
    }

    @Test
    fun `retryable registration failure shows an error and keeps the draft`() = runTest {
        val registration = FakeRegistrationRepository(RegisterOutcome.Retryable)
        val vm = viewModel(registration = registration)
        advanceUntilIdle()
        vm.acceptTerms()
        vm.submitProfile(controlDraft)

        vm.passPermissionSteps()
        advanceUntilIdle()

        assertTrue(vm.step is SetupStep.RegisterError)
        assertEquals(controlDraft, vm.draft)

        vm.backToForm()
        assertEquals(SetupStep.ProfileForm, vm.step)
    }

    @Test
    fun `rejected registration surfaces the server message`() = runTest {
        val registration = FakeRegistrationRepository(RegisterOutcome.Rejected("edad inválida"))
        val vm = viewModel(registration = registration)
        advanceUntilIdle()
        vm.acceptTerms(); vm.submitProfile(controlDraft)

        vm.passPermissionSteps()
        advanceUntilIdle()

        assertEquals("edad inválida", (vm.step as SetupStep.RegisterError).message)
    }

    @Test
    fun `retryRegistration re-invokes the repository`() = runTest {
        val registration = FakeRegistrationRepository(RegisterOutcome.Retryable)
        val vm = viewModel(registration = registration)
        advanceUntilIdle()
        vm.acceptTerms(); vm.submitProfile(controlDraft)
        vm.passPermissionSteps(); advanceUntilIdle()

        vm.retryRegistration()
        advanceUntilIdle()

        assertEquals(2, registration.calls)
    }

    @Test
    fun `keystore failure routes to KeystoreError then recovers on retry`() = runTest {
        val keystore = FakeKeystoreKeyManager(shouldFail = true)
        val setupState = FakeSetupStateRepository()
        val vm = viewModel(keystore = keystore, setupState = setupState)
        advanceUntilIdle()
        vm.acceptTerms(); vm.submitProfile(controlDraft)
        vm.passPermissionSteps(); advanceUntilIdle()

        assertEquals(SetupStep.KeystoreError, vm.step)
        assertFalse(setupState.isCompleteNow())
    }

    @Test
    fun `resume with credentials already persisted skips straight to key generation`() = runTest {
        val registration = FakeRegistrationRepository()
        val credentials = FakeCredentialStore(
            PersistedSession(
                userId = "u", authToken = "t", group = ExperimentGroup.CONTROL,
                signupTs = Instant.parse("2026-08-29T00:00:00Z"),
                profile = ParticipantProfile(21, "F", "Psicología", 34),
            ),
        )
        val setupState = FakeSetupStateRepository(initial = false)

        val vm = viewModel(registration = registration, credentials = credentials, setupState = setupState)
        advanceUntilIdle()

        assertEquals(SetupStep.Done, vm.step)
        assertEquals(0, registration.calls)
        assertTrue(setupState.isCompleteNow())
    }

    @Test
    fun `completing setup posts the recording notice once`() = runTest {
        val notifier = FakeStudyNotifier()
        val vm = viewModel(notifier = notifier)
        advanceUntilIdle()
        vm.acceptTerms()
        vm.submitProfile(controlDraft)
        vm.passPermissionSteps()
        advanceUntilIdle()

        assertEquals(SetupStep.Done, vm.step)
        assertEquals(listOf(StudyNotice.Recording(emptySet())), notifier.shown)
    }

    @Test
    fun `resuming a registered setup still posts the notice when it finishes`() = runTest {
        val notifier = FakeStudyNotifier()
        val credentials = FakeCredentialStore(
            PersistedSession(
                userId = "u", authToken = "t", group = ExperimentGroup.CONTROL,
                signupTs = Instant.parse("2026-09-01T00:00:00Z"),
                profile = ParticipantProfile(age = 21, gender = "F", universityMajor = "X", sasSvScore = 0),
            ),
        )
        viewModel(credentials = credentials, notifier = notifier)
        advanceUntilIdle()

        assertEquals(listOf(StudyNotice.Recording(emptySet())), notifier.shown)
    }

    @Test
    fun `a failed key generation posts nothing`() = runTest {
        val notifier = FakeStudyNotifier()
        val vm = viewModel(keystore = FakeKeystoreKeyManager(shouldFail = true), notifier = notifier)
        advanceUntilIdle()
        vm.acceptTerms()
        vm.submitProfile(controlDraft)
        vm.passPermissionSteps()
        advanceUntilIdle()

        assertTrue(notifier.shown.isEmpty())
    }

    @Test
    fun `completing setup announces a known cutoff in the recording notice`() = runTest {
        val notifier = FakeStudyNotifier()
        val cutoff = LocalDate.of(2026, 12, 1)
        val vm = viewModel(notifier = notifier, announcedCutoff = { cutoff })
        advanceUntilIdle()
        vm.acceptTerms()
        vm.submitProfile(controlDraft)
        vm.passPermissionSteps()
        advanceUntilIdle()

        assertEquals(listOf(StudyNotice.Recording(emptySet(), cutoff)), notifier.shown)
    }

    @Test
    fun `on a listed brand the battery step leads to the autostart step, not registration`() = runTest {
        val registration = FakeRegistrationRepository()
        val vm = viewModel(registration = registration, needsAutostartStep = true)
        advanceUntilIdle()
        vm.acceptTerms()
        vm.submitProfile(controlDraft)

        vm.passPermissionSteps()

        assertEquals(SetupStep.Autostart, vm.step)
        assertEquals(0, registration.calls)
    }

    @Test
    fun `continuing from the autostart step always registers, since nothing can be verified`() = runTest {
        val registration = FakeRegistrationRepository()
        val vm = viewModel(registration = registration, needsAutostartStep = true)
        advanceUntilIdle()
        vm.acceptTerms()
        vm.submitProfile(controlDraft)
        vm.passPermissionSteps()

        vm.onAutostartContinue()
        advanceUntilIdle()

        assertEquals(SetupStep.Done, vm.step)
        assertEquals(1, registration.calls)
    }

    @Test
    fun `autostart continue does nothing on any other step`() = runTest {
        val registration = FakeRegistrationRepository()
        val vm = viewModel(registration = registration, needsAutostartStep = true)
        advanceUntilIdle()

        vm.onAutostartContinue()

        assertEquals(SetupStep.Terms, vm.step)
        assertEquals(0, registration.calls)
    }
}
