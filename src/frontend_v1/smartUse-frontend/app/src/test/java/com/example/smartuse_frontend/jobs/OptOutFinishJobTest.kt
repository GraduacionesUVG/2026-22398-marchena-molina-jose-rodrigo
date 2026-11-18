package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.network.dto.CutoffResponse
import com.example.smartuse_frontend.config.ExperimentGroup
import com.example.smartuse_frontend.data.FakeCredentialStore
import com.example.smartuse_frontend.data.OptOutKind
import com.example.smartuse_frontend.data.ParticipantProfile
import com.example.smartuse_frontend.data.PersistedSession
import com.example.smartuse_frontend.data.db.CanonicalSyncOutcome
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private class StubApi(
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

class OptOutFinishJobTest {

    private fun job(
        stateStore: JobStateStore,
        api: SmartUseApi = StubApi(),
        runDrainCycle: suspend () -> CanonicalSyncOutcome = { CanonicalSyncOutcome.Success(0, null, null) },
        cancelAllCount: IntArray = IntArray(1),
        wipeCount: IntArray = IntArray(1),
        credentialStore: FakeCredentialStore = FakeCredentialStore(session()),
        notifier: FakeStudyNotifier = FakeStudyNotifier(),
    ) = OptOutFinishJob(
        stateStore = stateStore,
        credentialStore = credentialStore,
        api = api,
        runDrainCycle = runDrainCycle,
        cancelAll = { cancelAllCount[0]++ },
        wipeLocalData = { wipeCount[0]++ },
        notifier = notifier,
        logger = {},
    )

    @Test
    fun `nothing pending finishes immediately without touching the network`() = runTest {
        val stateStore = FakeJobStateStore()

        assertTrue(job(stateStore).run())
    }

    @Test
    fun `soft drains to completion then acks and wipes`() = runTest {
        val stateStore = FakeJobStateStore().apply {
            setOptedOutAt(Instant.parse("2026-09-22T10:00:00Z"))
            setOptOutKind(OptOutKind.SOFT)
        }
        var drainCalls = 0
        val outcomes = listOf(
            CanonicalSyncOutcome.Success(500, null, null, hasMore = true),
            CanonicalSyncOutcome.Success(10, null, null, hasMore = false),
        )
        val cancelAllCount = IntArray(1)
        val wipeCount = IntArray(1)

        val finished = job(
            stateStore, runDrainCycle = { outcomes[drainCalls++] },
            cancelAllCount = cancelAllCount, wipeCount = wipeCount,
        ).run()

        assertTrue(finished)
        assertEquals(2, drainCalls)
        assertEquals(1, cancelAllCount[0])
        assertEquals(1, wipeCount[0])
    }

    @Test
    fun `soft still failing to drain reports not finished`() = runTest {
        val stateStore = FakeJobStateStore().apply {
            setOptedOutAt(Instant.parse("2026-09-22T10:00:00Z"))
            setOptOutKind(OptOutKind.SOFT)
        }

        val finished = job(
            stateStore,
            runDrainCycle = { CanonicalSyncOutcome.Failed("no network", FailureKind.TRANSIENT) },
        ).run()

        assertFalse(finished)
    }

    @Test
    fun `hard acks and wipes, and records the confirmation`() = runTest {
        val stateStore = FakeJobStateStore().apply {
            setOptedOutAt(Instant.parse("2026-09-22T10:00:00Z"))
            setOptOutKind(OptOutKind.HARD)
        }
        val wipeCount = IntArray(1)

        val finished = job(stateStore, wipeCount = wipeCount).run()

        assertTrue(finished)
        assertTrue(stateStore.hardDeleteConfirmed())
        assertEquals(1, wipeCount[0])
    }

    @Test
    fun `hard still failing to ack reports not finished and does not wipe`() = runTest {
        val stateStore = FakeJobStateStore().apply {
            setOptedOutAt(Instant.parse("2026-09-22T10:00:00Z"))
            setOptOutKind(OptOutKind.HARD)
        }
        val wipeCount = IntArray(1)

        val finished = job(
            stateStore,
            api = StubApi(optOutHardResult = NetworkResult.HttpError(401, null, null)),
            wipeCount = wipeCount,
        ).run()

        assertFalse(finished)
        assertFalse(stateStore.hardDeleteConfirmed())
        assertEquals(0, wipeCount[0])
    }

    @Test
    fun `no auth token reports not finished without touching the network`() = runTest {
        val stateStore = FakeJobStateStore().apply {
            setOptedOutAt(Instant.parse("2026-09-22T10:00:00Z"))
            setOptOutKind(OptOutKind.HARD)
        }

        val finished = job(stateStore, credentialStore = FakeCredentialStore(null)).run()

        assertFalse(finished)
    }

    @Test
    fun `a hard ack that finally succeeds shows done`() = runTest {
        val stateStore = FakeJobStateStore().apply { setOptOutKind(OptOutKind.HARD) }
        val notifier = FakeStudyNotifier()

        job(stateStore, notifier = notifier).run()

        assertEquals(listOf(StudyNotice.HardOptOut(HardOptOutStage.DONE)), notifier.shown)
    }

    @Test
    fun `a hard ack still failing shows failed`() = runTest {
        val stateStore = FakeJobStateStore().apply { setOptOutKind(OptOutKind.HARD) }
        val notifier = FakeStudyNotifier()
        val api = StubApi(optOutHardResult = NetworkResult.IoError(java.io.IOException("offline")))

        job(stateStore, api = api, notifier = notifier).run()

        assertEquals(listOf(StudyNotice.HardOptOut(HardOptOutStage.FAILED)), notifier.shown)
    }

    @Test
    fun `a soft finish never touches the notification`() = runTest {
        val stateStore = FakeJobStateStore().apply { setOptOutKind(OptOutKind.SOFT) }
        val notifier = FakeStudyNotifier()

        job(stateStore, notifier = notifier).run()

        assertTrue(notifier.shown.isEmpty())
        assertEquals(0, notifier.clears)
    }

    @Test
    fun `an already confirmed hard delete finishes without calling the backend or posting`() = runTest {
        val stateStore = FakeJobStateStore().apply {
            setOptOutKind(OptOutKind.HARD)
            setHardDeleteConfirmed(true)
        }
        val notifier = FakeStudyNotifier()
        var called = false
        val api = object : SmartUseApi by StubApi() {
            override suspend fun optOutHard(authToken: String): NetworkResult<OptOutAckResponse> {
                called = true
                return NetworkResult.IoError(java.io.IOException("gone"))
            }
        }

        assertTrue(job(stateStore, api = api, notifier = notifier).run())
        assertFalse(called)
        assertTrue(notifier.shown.isEmpty())
    }

    @Test
    fun `a failure never overwrites a delete the app confirmed meanwhile`() = runTest {
        val stateStore = FakeJobStateStore().apply { setOptOutKind(OptOutKind.HARD) }
        val notifier = FakeStudyNotifier()
        val api = object : SmartUseApi by StubApi() {
            override suspend fun optOutHard(authToken: String): NetworkResult<OptOutAckResponse> {
                stateStore.setHardDeleteConfirmed(true) // the in-app retry won the race
                return NetworkResult.IoError(java.io.IOException("already deleted"))
            }
        }

        assertTrue(job(stateStore, api = api, notifier = notifier).run())
        assertTrue(notifier.shown.isEmpty())
    }
}
