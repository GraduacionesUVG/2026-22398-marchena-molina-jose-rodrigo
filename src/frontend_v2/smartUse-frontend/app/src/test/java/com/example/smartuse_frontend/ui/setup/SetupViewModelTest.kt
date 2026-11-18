package com.example.smartuse_frontend.ui.setup

import com.example.smartuse_frontend.data.FakeCredentialStore
import com.example.smartuse_frontend.data.FakeKeystoreKeyManager
import com.example.smartuse_frontend.data.FakePermissionChecker
import com.example.smartuse_frontend.data.FakeRegistrationRepository
import com.example.smartuse_frontend.data.FakeSetupStateRepository
import com.example.smartuse_frontend.data.ParticipantProfile
import com.example.smartuse_frontend.data.PersistedSession
import com.example.smartuse_frontend.data.RegisterOutcome
import com.example.smartuse_frontend.data.RegistrationRepository
import com.example.smartuse_frontend.data.SetupDraft
import com.example.smartuse_frontend.notification.FakeStudyNotifier
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SetupViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val controlDraft = SetupDraft(
        age = 21, gender = "Femenino", universityMajor = "Psicología",
        sasSvScore = 34,
    )

    private fun viewModel(
        registration: RegistrationRepository = FakeRegistrationRepository(),
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
        earlierUsageCheck: suspend (Instant, Instant) -> Boolean = { _, _ -> false },
        now: () -> Instant = { fixedNow },
        buildProfile: suspend () -> Unit = {},
    ) = SetupViewModel(
        registration, credentials, keystore, setupState, permissions,
        studyNotifier = notifier,
        announcedCutoff = announcedCutoff,
        needsAutostartStep = needsAutostartStep,
        earlierUsageCheck = earlierUsageCheck,
        now = now,
        zone = { zone },
        buildProfile = buildProfile,
        ioDispatcher = mainDispatcherRule.dispatcher,
    )

    private val zone = ZoneId.of("America/Guatemala")
    private val fixedNow = Instant.parse("2026-10-21T15:00:00Z")
    private val hasHistory: suspend (Instant, Instant) -> Boolean = { _, _ -> true }

    /** Fails once with [first], then registers. */
    private class FlakyRegistration(private val first: RegisterOutcome) : RegistrationRepository {
        var calls = 0
        override suspend fun register(draft: SetupDraft): RegisterOutcome =
            if (calls++ == 0) first else RegisterOutcome.Registered("u", "t", Instant.parse("2026-10-21T15:00:00Z"))
    }

    /** From the terms to the earlier-usage step (or past it, without history). */
    private fun SetupViewModel.throughPermissions() {
        acceptTerms()
        submitProfile(controlDraft)
        passPermissionSteps()
    }

    // All four permission steps, in order, with everything granted — the run-up to registration.
    private fun SetupViewModel.passPermissionSteps() {
        onUsageAccessContinue()
        onNotificationAccessContinue()
        onBatteryOptimizationContinue()
        onOverlayAccessContinue()
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
        assertEquals(SetupStep.OverlayAccess, vm.step)
        assertEquals(0, registration.calls)

        vm.onOverlayAccessContinue()
        advanceUntilIdle()

        assertEquals(SetupStep.Done, vm.step)
        assertEquals(1, registration.calls)
        assertEquals(1, credentials.saveCount)
        assertTrue(keystore.generated)
        assertTrue(setupState.isCompleteNow())
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
    fun `battery exemption gate blocks the next step until granted`() = runTest {
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
        assertEquals(SetupStep.OverlayAccess, vm.step)
        assertEquals(0, registration.calls)
    }

    @Test
    fun `overlay gate blocks registration until granted`() = runTest {
        val registration = FakeRegistrationRepository()
        val permissions = FakePermissionChecker(overlayGranted = false)
        val vm = viewModel(registration = registration, permissions = permissions)
        advanceUntilIdle()
        vm.acceptTerms()
        vm.submitProfile(controlDraft)
        vm.onUsageAccessContinue()
        vm.onNotificationAccessContinue()
        vm.onBatteryOptimizationContinue()

        vm.onOverlayAccessContinue()
        advanceUntilIdle()
        assertEquals(SetupStep.OverlayAccess, vm.step)
        assertEquals(0, registration.calls)

        permissions.overlayGranted = true
        vm.onOverlayAccessContinue()
        advanceUntilIdle()
        assertEquals(SetupStep.Done, vm.step)
        assertEquals(1, registration.calls)
    }

    @Test
    fun `overlay continue is ignored outside its own step`() = runTest {
        val registration = FakeRegistrationRepository()
        val vm = viewModel(registration = registration)
        advanceUntilIdle()
        vm.acceptTerms()
        vm.submitProfile(controlDraft)
        vm.onUsageAccessContinue()
        vm.onNotificationAccessContinue()

        vm.onOverlayAccessContinue() // still on BatteryOptimization
        advanceUntilIdle()

        assertEquals(SetupStep.BatteryOptimization, vm.step)
        assertEquals(0, registration.calls)
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
                userId = "u", authToken = "t",                signupTs = Instant.parse("2026-08-29T00:00:00Z"),
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
                userId = "u", authToken = "t",                signupTs = Instant.parse("2026-09-01T00:00:00Z"),
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
    fun `on a listed brand the overlay step leads to the autostart step, not registration`() = runTest {
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

    @Test
    fun `with history the step comes after overlay`() = runTest {
        val registration = FakeRegistrationRepository()
        val vm = viewModel(registration = registration, earlierUsageCheck = hasHistory)
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        assertEquals(SetupStep.EarlierUsage, vm.step)
        assertEquals(0, registration.calls)
    }

    @Test
    fun `with history and autostart the step comes after autostart`() = runTest {
        val vm = viewModel(earlierUsageCheck = hasHistory, needsAutostartStep = true)
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        assertEquals(SetupStep.Autostart, vm.step)
        vm.onAutostartContinue()
        advanceUntilIdle()
        assertEquals(SetupStep.EarlierUsage, vm.step)
    }

    @Test
    fun `no history skips the step`() = runTest {
        val setupState = FakeSetupStateRepository()
        val vm = viewModel(setupState = setupState)
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        assertEquals(SetupStep.Done, vm.step)
        assertNull(setupState.historyStart())
    }

    @Test
    fun `a throwing check skips the step`() = runTest {
        val vm = viewModel(earlierUsageCheck = { _, _ -> throw SecurityException("no usage access") })
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        assertEquals(SetupStep.Done, vm.step)
    }

    @Test
    fun `the check is asked about the window`() = runTest {
        var asked: Pair<Instant, Instant>? = null
        val vm = viewModel(earlierUsageCheck = { s, e -> asked = s to e; false })
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        assertEquals(historyStart(fixedNow, zone) to windowEnd(fixedNow, zone), asked)
    }

    @Test
    fun `accept stores the window`() = runTest {
        val setupState = FakeSetupStateRepository()
        val vm = viewModel(setupState = setupState, earlierUsageCheck = hasHistory)
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        vm.acceptEarlierUsage()
        advanceUntilIdle()
        assertEquals(historyStart(fixedNow, zone), setupState.historyStart())
        assertEquals(SetupStep.Done, vm.step)
    }

    @Test
    fun `an accepted history builds the profile before setup completes`() = runTest {
        val setupState = FakeSetupStateRepository()
        val completeAtBuild = mutableListOf<Boolean>()
        val vm = viewModel(
            setupState = setupState,
            earlierUsageCheck = hasHistory,
            buildProfile = { completeAtBuild += setupState.isCompleteNow() },
        )
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        vm.acceptEarlierUsage()
        advanceUntilIdle()
        assertEquals(listOf(false), completeAtBuild)
        assertTrue(setupState.isCompleteNow())
        assertEquals(SetupStep.Done, vm.step)
    }

    @Test
    fun `while the profile builds the step says so and setup is not complete`() = runTest {
        val setupState = FakeSetupStateRepository()
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(setupState = setupState, earlierUsageCheck = hasHistory, buildProfile = { gate.await() })
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        vm.acceptEarlierUsage()
        runCurrent() // not advanceUntilIdle: that would skip past the time limit
        assertEquals(SetupStep.BuildingProfile, vm.step)
        assertFalse(setupState.isCompleteNow())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SetupStep.Done, vm.step)
    }

    @Test
    fun `a rejected history never builds the profile`() = runTest {
        var builds = 0
        val vm = viewModel(earlierUsageCheck = hasHistory, buildProfile = { builds++ })
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        vm.rejectEarlierUsage()
        advanceUntilIdle()
        assertEquals(0, builds)
        assertEquals(SetupStep.Done, vm.step)
    }

    @Test
    fun `no history never builds the profile`() = runTest {
        var builds = 0
        val vm = viewModel(buildProfile = { builds++ })
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        assertEquals(0, builds)
    }

    @Test
    fun `a failing profile build still finishes setup`() = runTest {
        val setupState = FakeSetupStateRepository()
        val vm = viewModel(
            setupState = setupState,
            earlierUsageCheck = hasHistory,
            buildProfile = { throw IllegalStateException("capture failed") },
        )
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        vm.acceptEarlierUsage()
        advanceUntilIdle()
        assertTrue(setupState.isCompleteNow())
        assertEquals(SetupStep.Done, vm.step)
    }

    @Test
    fun `a profile build past the time limit still finishes setup`() = runTest {
        val setupState = FakeSetupStateRepository()
        val vm = viewModel(setupState = setupState, earlierUsageCheck = hasHistory, buildProfile = { awaitCancellation() })
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        vm.acceptEarlierUsage()
        advanceUntilIdle()
        assertTrue(setupState.isCompleteNow())
        assertEquals(SetupStep.Done, vm.step)
    }

    @Test
    fun `a resumed setup with an accepted history builds the profile`() = runTest {
        var builds = 0
        val credentials = FakeCredentialStore(PersistedSession("u", "t", fixedNow, ParticipantProfile(21, "F", "P", 40)))
        val setupState = FakeSetupStateRepository().apply { historyStartValue = historyStart(fixedNow, zone) }
        viewModel(credentials = credentials, setupState = setupState, buildProfile = { builds++ })
        advanceUntilIdle()
        assertEquals(1, builds)
        assertTrue(setupState.isCompleteNow())
    }

    @Test
    fun `reject stores nothing`() = runTest {
        val setupState = FakeSetupStateRepository()
        val vm = viewModel(setupState = setupState, earlierUsageCheck = hasHistory)
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        vm.rejectEarlierUsage()
        advanceUntilIdle()
        assertNull(setupState.historyStart())
        assertEquals(SetupStep.Done, vm.step)
    }

    @Test
    fun `reject clears an earlier accept`() = runTest {
        val setupState = FakeSetupStateRepository().apply { historyStartValue = Instant.EPOCH }
        val vm = viewModel(setupState = setupState, earlierUsageCheck = hasHistory)
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        vm.rejectEarlierUsage()
        advanceUntilIdle()
        assertNull(setupState.historyStart())
    }

    @Test
    fun `no history clears an earlier accept`() = runTest {
        val setupState = FakeSetupStateRepository().apply { historyStartValue = Instant.EPOCH }
        val vm = viewModel(setupState = setupState)
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        assertNull(setupState.historyStart())
    }

    @Test
    fun `accept and reject do nothing on any other step`() = runTest {
        val setupState = FakeSetupStateRepository()
        val vm = viewModel(setupState = setupState)
        advanceUntilIdle()
        vm.acceptEarlierUsage()
        vm.rejectEarlierUsage()
        advanceUntilIdle()
        assertEquals(SetupStep.Terms, vm.step)
        assertNull(setupState.historyStart())
    }

    @Test
    fun `retry keeps the stored window`() = runTest {
        val setupState = FakeSetupStateRepository()
        val flaky = FlakyRegistration(RegisterOutcome.Retryable)
        var clock = fixedNow
        val vm = viewModel(registration = flaky, setupState = setupState, earlierUsageCheck = hasHistory, now = { clock })
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        vm.acceptEarlierUsage()
        advanceUntilIdle()
        assertTrue(vm.step is SetupStep.RegisterError)

        clock = fixedNow.plusSeconds(86_400) // the retry happens after midnight
        vm.retryRegistration()
        advanceUntilIdle()
        assertEquals(SetupStep.Done, vm.step)
        assertEquals(historyStart(fixedNow, zone), setupState.historyStart())
        assertEquals(2, flaky.calls)
    }

    @Test
    fun `back to form does not re-ask`() = runTest {
        var checks = 0
        val vm = viewModel(
            registration = FlakyRegistration(RegisterOutcome.Rejected("edad invalida")),
            earlierUsageCheck = { _, _ -> checks++; true },
        )
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        vm.rejectEarlierUsage()
        advanceUntilIdle()
        assertTrue(vm.step is SetupStep.RegisterError)

        vm.backToForm()
        vm.submitProfile(controlDraft)
        vm.passPermissionSteps()
        advanceUntilIdle()
        assertEquals(SetupStep.Done, vm.step)
        assertEquals(1, checks)
    }

    @Test
    fun `a second continue during the check is ignored`() = runTest {
        var checks = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vm = viewModel(earlierUsageCheck = { _, _ -> checks++; gate.await(); true })
        advanceUntilIdle()
        vm.throughPermissions()
        advanceUntilIdle()
        vm.onOverlayAccessContinue()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, checks)
        assertEquals(SetupStep.EarlierUsage, vm.step)
    }
}
