package com.example.smartuse_frontend.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.smartuse_frontend.network.dto.CutoffResponse
import com.example.smartuse_frontend.config.ExperimentGroup
import com.example.smartuse_frontend.data.db.CanonicalSyncOutcome
import com.example.smartuse_frontend.jobs.FakeJobStateStore
import com.example.smartuse_frontend.network.FailureKind
import com.example.smartuse_frontend.network.NetworkResult
import com.example.smartuse_frontend.network.SmartUseApi
import com.example.smartuse_frontend.network.dto.CanonicalCursorResponse
import com.example.smartuse_frontend.network.dto.CanonicalSyncRequest
import com.example.smartuse_frontend.network.dto.CanonicalSyncResponse
import com.example.smartuse_frontend.network.dto.OptOutAckResponse
import com.example.smartuse_frontend.network.dto.RegisterRequest
import com.example.smartuse_frontend.network.dto.RegisterResponse
import com.example.smartuse_frontend.notification.FakeStudyNotifier
import com.example.smartuse_frontend.notification.HardOptOutStage
import com.example.smartuse_frontend.notification.StudyNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private class OptOutStubApi(
    private val optOutSoftResult: NetworkResult<OptOutAckResponse> =
        NetworkResult.Success(OptOutAckResponse("opted_out_soft", "2026-09-22T10:00:00Z")),
    private val optOutHardResult: NetworkResult<OptOutAckResponse> =
        NetworkResult.Success(OptOutAckResponse("opted_out_hard", "2026-09-22T10:00:00Z")),
) : SmartUseApi {
    override suspend fun register(body: RegisterRequest) = throw NotImplementedError()
    override suspend fun syncCanonical(authToken: String, body: CanonicalSyncRequest) = throw NotImplementedError()
    override suspend fun getCanonicalCursor(authToken: String) = throw NotImplementedError()
    override suspend fun getCutoff(): NetworkResult<CutoffResponse> =
        throw NotImplementedError("not exercised by this test class")
    override suspend fun optOutSoft(authToken: String): NetworkResult<OptOutAckResponse> = optOutSoftResult
    override suspend fun optOutHard(authToken: String): NetworkResult<OptOutAckResponse> = optOutHardResult
}

private fun session() = PersistedSession(
    userId = "u", authToken = "tok", group = ExperimentGroup.CONTROL,
    signupTs = Instant.parse("2026-09-01T00:00:00Z"),
    profile = ParticipantProfile(age = 21, gender = "F", universityMajor = "X", sasSvScore = 1),
)

class RealOptOutRepositoryTest {

    private val dispatcher = StandardTestDispatcher()
    private val fixedClock = Clock.fixed(Instant.parse("2026-09-22T10:00:00Z"), ZoneOffset.UTC)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun repository(
        api: SmartUseApi = OptOutStubApi(),
        stateStore: FakeJobStateStore = FakeJobStateStore(),
        credentialStore: FakeCredentialStore = FakeCredentialStore(session()),
        runDrainCycle: suspend () -> CanonicalSyncOutcome = { CanonicalSyncOutcome.Success(0, null, null) },
        cancelCaptureCount: IntArray = IntArray(1),
        cancelAllCount: IntArray = IntArray(1),
        scheduleFinishWorkerCount: IntArray = IntArray(1),
        wipeCount: IntArray = IntArray(1),
        notifier: FakeStudyNotifier = FakeStudyNotifier(),
        scope: CoroutineScope = TestScope(dispatcher),
        refreshCutoff: suspend () -> Unit = {},
        isWindowOpen: suspend () -> Boolean = { true },
        endCollection: suspend () -> Unit = {},
    ) = RealOptOutRepository(
        stateStore = stateStore,
        credentialStore = credentialStore,
        api = api,
        cancelCapture = { cancelCaptureCount[0]++ },
        cancelAll = { cancelAllCount[0]++ },
        runDrainCycle = runDrainCycle,
        scheduleFinishWorker = { scheduleFinishWorkerCount[0]++ },
        wipeLocalData = { wipeCount[0]++ },
        notifier = notifier,
        backgroundScope = scope,
        clock = fixedClock,
        refreshCutoff = refreshCutoff,
        isWindowOpen = isWindowOpen,
        endCollection = endCollection,
    )

    @Test
    fun `soft opt-out stops capture and returns success before the drain resolves`() = runTest {
        val stateStore = FakeJobStateStore()
        val cancelCaptureCount = IntArray(1)
        var drainStarted = false
        val repo = repository(
            stateStore = stateStore,
            cancelCaptureCount = cancelCaptureCount,
            runDrainCycle = {
                drainStarted = true
                CanonicalSyncOutcome.Success(0, null, null)
            },
        )

        val result = repo.optOutSoft()

        assertEquals(OptOutResult.Success, result)
        assertEquals(1, cancelCaptureCount[0])
        assertEquals(true, stateStore.optedOutAt() != null)
        assertEquals(OptOutKind.SOFT, stateStore.optOutKind())
        // The background drain hasn't run yet — the test dispatcher hasn't advanced.
        assertFalse(drainStarted)
    }

    @Test
    fun `a fully drained soft opt-out acks, cancels the rest, and wipes`() = runTest {
        val cancelAllCount = IntArray(1)
        val wipeCount = IntArray(1)
        val repo = repository(cancelAllCount = cancelAllCount, wipeCount = wipeCount)

        repo.optOutSoft()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, cancelAllCount[0])
        assertEquals(1, wipeCount[0])
    }

    @Test
    fun `a soft drain that never finishes schedules the finish worker and never wipes`() = runTest {
        val scheduleCount = IntArray(1)
        val wipeCount = IntArray(1)
        val repo = repository(
            runDrainCycle = { CanonicalSyncOutcome.Failed("offline", FailureKind.TRANSIENT) },
            scheduleFinishWorkerCount = scheduleCount,
            wipeCount = wipeCount,
        )

        repo.optOutSoft()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, scheduleCount[0])
        assertEquals(0, wipeCount[0])
    }

    @Test
    fun `a failed soft ack schedules the finish worker and never wipes`() = runTest {
        val scheduleCount = IntArray(1)
        val wipeCount = IntArray(1)
        val repo = repository(
            api = OptOutStubApi(optOutSoftResult = NetworkResult.HttpError(500, null, null)),
            scheduleFinishWorkerCount = scheduleCount,
            wipeCount = wipeCount,
        )

        repo.optOutSoft()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, scheduleCount[0])
        assertEquals(0, wipeCount[0])
    }

    @Test
    fun `hard opt-out cancels everything synchronously before the ack resolves`() = runTest {
        val stateStore = FakeJobStateStore()
        val cancelAllCount = IntArray(1)
        val repo = repository(stateStore = stateStore, cancelAllCount = cancelAllCount)

        val result = repo.optOutHard()

        assertEquals(OptOutResult.Success, result)
        assertEquals(1, cancelAllCount[0])
        assertEquals(OptOutKind.HARD, stateStore.optOutKind())
    }

    @Test
    fun `a successful hard ack confirms the delete and wipes`() = runTest {
        val stateStore = FakeJobStateStore()
        val wipeCount = IntArray(1)
        val repo = repository(stateStore = stateStore, wipeCount = wipeCount)

        repo.optOutHard()

        assertTrue(stateStore.hardDeleteConfirmed())
        assertEquals(1, wipeCount[0])
        assertEquals(OptOutStatus.Hard(deleteConfirmed = true, attemptFailed = false), repo.status())
    }

    @Test
    fun `a failed hard ack surfaces through status and schedules a retry`() = runTest {
        val stateStore = FakeJobStateStore()
        val scheduleCount = IntArray(1)
        val repo = repository(
            stateStore = stateStore,
            api = OptOutStubApi(optOutHardResult = NetworkResult.HttpError(500, null, null)),
            scheduleFinishWorkerCount = scheduleCount,
        )

        repo.optOutHard()

        assertFalse(stateStore.hardDeleteConfirmed())
        assertEquals(1, scheduleCount[0])
        val status = repo.status() as OptOutStatus.Hard
        assertFalse(status.deleteConfirmed)
        assertTrue(status.attemptFailed)
    }

    @Test
    fun `status reflects NotOptedOut before anything happens`() = runTest {
        assertEquals(OptOutStatus.NotOptedOut, repository().status())
    }

    @Test
    fun `soft opt-out removes the notification and posts nothing`() = runTest {
        val notifier = FakeStudyNotifier()

        repository(notifier = notifier).optOutSoft()

        assertEquals(1, notifier.clears)
        assertTrue(notifier.shown.isEmpty())
    }

    @Test
    fun `a successful hard opt-out shows pending then done`() = runTest {
        val notifier = FakeStudyNotifier()

        repository(notifier = notifier).optOutHard()

        assertEquals(
            listOf(StudyNotice.HardOptOut(HardOptOutStage.PENDING), StudyNotice.HardOptOut(HardOptOutStage.DONE)),
            notifier.shown,
        )
    }

    @Test
    fun `a failed hard ack shows pending then failed`() = runTest {
        val notifier = FakeStudyNotifier()
        val api = OptOutStubApi(optOutHardResult = NetworkResult.IoError(java.io.IOException("offline")))

        repository(api = api, notifier = notifier).optOutHard()

        assertEquals(
            listOf(StudyNotice.HardOptOut(HardOptOutStage.PENDING), StudyNotice.HardOptOut(HardOptOutStage.FAILED)),
            notifier.shown,
        )
    }

    @Test
    fun `a hard opt-out without a session shows failed, not pending forever`() = runTest {
        val notifier = FakeStudyNotifier()

        repository(credentialStore = FakeCredentialStore(), notifier = notifier).optOutHard()

        assertEquals(StudyNotice.HardOptOut(HardOptOutStage.FAILED), notifier.shown.last())
    }

    @Test
    fun `hard opt-out queues the finish worker before calling the backend`() = runTest {
        val scheduleCount = IntArray(1)
        var queuedBeforeCall = false
        val api = object : SmartUseApi by OptOutStubApi() {
            override suspend fun optOutHard(authToken: String): NetworkResult<OptOutAckResponse> {
                queuedBeforeCall = scheduleCount[0] > 0
                return NetworkResult.Success(OptOutAckResponse("opted_out_hard", "2026-09-22T10:00:00Z"))
            }
        }

        repository(api = api, scheduleFinishWorkerCount = scheduleCount).optOutHard()

        assertTrue(queuedBeforeCall)
    }

    @Test
    fun `a hard opt-out after the cutoff runs the ending and sends nothing`() = runTest {
        val stateStore = FakeJobStateStore()
        val events = mutableListOf<String>()
        val api = object : SmartUseApi by OptOutStubApi() {
            override suspend fun optOutHard(authToken: String): NetworkResult<OptOutAckResponse> {
                events += "POST /opt-out/hard"
                return NetworkResult.Success(OptOutAckResponse("opted_out_hard", "2026-09-22T10:00:00Z"))
            }
        }
        val cancelAllCount = IntArray(1)
        val repo = repository(
            api = api,
            stateStore = stateStore,
            cancelAllCount = cancelAllCount,
            refreshCutoff = { events += "refresh" },
            isWindowOpen = { events += "check"; false },
            endCollection = { events += "end" },
        )

        val result = repo.optOutHard()

        assertEquals(OptOutResult.CollectionEnded, result)
        assertEquals(listOf("refresh", "check", "end"), events)
        assertEquals(null, stateStore.optedOutAt())
        assertEquals(null, stateStore.optOutKind())
        assertEquals(0, cancelAllCount[0])
    }

    @Test
    fun `a hard opt-out before the cutoff refreshes it first, then runs as before`() = runTest {
        val events = mutableListOf<String>()
        val stateStore = FakeJobStateStore()
        val repo = repository(
            stateStore = stateStore,
            refreshCutoff = { events += "refresh" },
            isWindowOpen = { events += "check"; true },
            endCollection = { events += "end" },
        )

        val result = repo.optOutHard()

        assertEquals(OptOutResult.Success, result)
        assertEquals(listOf("refresh", "check"), events)
        assertEquals(OptOutKind.HARD, stateStore.optOutKind())
        assertEquals(true, stateStore.hardDeleteConfirmed())
    }

    @Test
    fun `soft opt-out never checks the cutoff`() = runTest {
        val events = mutableListOf<String>()
        val repo = repository(
            refreshCutoff = { events += "refresh" },
            isWindowOpen = { events += "check"; false },
            endCollection = { events += "end" },
        )

        repo.optOutSoft()

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `leaving the screen during the cutoff check never loses the withdrawal`() = runTest {
        val stateStore = FakeJobStateStore()
        val repo = repository(stateStore = stateStore, refreshCutoff = { delay(3_000) })

        val caller = launch { repo.optOutHard() }
        testScheduler.advanceTimeBy(1_000)
        caller.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(OptOutKind.HARD, stateStore.optOutKind())
        assertEquals(true, stateStore.hardDeleteConfirmed())
    }

    // MainActivity routes to the terminal screen as soon as the opt-out time is
    // stored; a kind written after it made that screen read "not opted out" and
    // show no deletion status at all.
    @Test
    fun `the kind is already stored when the app first sees the opt-out`() = runTest {
        val stateStore = FakeJobStateStore()
        var kindWhenRouted: OptOutKind? = null
        val watcher = launch(UnconfinedTestDispatcher(testScheduler)) {
            stateStore.hasOptedOut.first { it }
            kindWhenRouted = stateStore.optOutKind()
        }

        repository(stateStore = stateStore).optOutHard()
        watcher.join()

        assertEquals(OptOutKind.HARD, kindWhenRouted)
    }

    // The terminal screen follows the deletion as it resolves: it opens while the
    // request is still in flight and must not freeze on what it first read.
    @Test
    fun `status updates follow a hard delete from pending to confirmed`() = runTest {
        val backendAnswers = CompletableDeferred<Unit>()
        val api = object : SmartUseApi by OptOutStubApi() {
            override suspend fun optOutHard(authToken: String): NetworkResult<OptOutAckResponse> {
                backendAnswers.await()
                return NetworkResult.Success(OptOutAckResponse("opted_out_hard", "2026-09-22T10:00:00Z"))
            }
        }
        val repo = repository(api = api)
        val seen = mutableListOf<OptOutStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.statusUpdates.collect { seen += it } }

        launch { repo.optOutHard() }
        testScheduler.advanceUntilIdle()
        assertEquals(OptOutStatus.Hard(deleteConfirmed = false, attemptFailed = false), seen.last())

        backendAnswers.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(OptOutStatus.Hard(deleteConfirmed = true, attemptFailed = false), seen.last())
    }

    @Test
    fun `status updates show a failed attempt while the worker keeps retrying`() = runTest {
        val repo = repository(api = OptOutStubApi(optOutHardResult = NetworkResult.HttpError(500, null, null)))
        val seen = mutableListOf<OptOutStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.statusUpdates.collect { seen += it } }

        repo.optOutHard()
        testScheduler.advanceUntilIdle()

        assertEquals(OptOutStatus.Hard(deleteConfirmed = false, attemptFailed = true), seen.last())
    }
}
