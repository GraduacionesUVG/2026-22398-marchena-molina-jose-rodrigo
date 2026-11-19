package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.network.FailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class JobExecutorTest {

    private val now = Instant.parse("2026-09-20T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val store = FakeJobStateStore()
    private val logs = mutableListOf<String>()
    private var cancelCount = 0
    private val events = mutableListOf<String>()

    private fun gateFor(result: GateResult) = JobGate(
        hasSession = { result != GateResult.NO_SESSION },
        isWindowOpen = { result != GateResult.WINDOW_CLOSED },
        isOptedOut = { result == GateResult.OPTED_OUT },
        isCollectionEnded = { result == GateResult.COLLECTION_ENDED },
    )

    private fun executor(
        gate: GateResult = GateResult.OPEN,
        onStudyEnded: suspend () -> Unit = { events += "ended" },
    ) = JobExecutor(
        gate = gateFor(gate),
        stateStore = store,
        clock = clock,
        standbyBucket = { 10 },
        cancelAll = { cancelCount++; events += "cancel" },
        onStudyEnded = onStudyEnded,
        logger = { logs += it },
        maxAttempts = 5,
    )

    private fun failed(kind: FailureKind, message: String = "boom") = JobOutcome.Failed(kind, message)

    @Test
    fun `no session skips quietly without running, recording or cancelling`() = runBlocking {
        var ran = false

        val decision = executor(GateResult.NO_SESSION).execute(JobName.SYNC, 0) { ran = true; JobOutcome.Success() }

        assertEquals(WorkDecision.SUCCESS, decision)
        assertFalse(ran)
        assertEquals(0, cancelCount)
        assertEquals(JobState(), store.get(JobName.SYNC))
    }

    @Test
    fun `a closed window, an opt-out or an ended collection skips and cancels every job`() = runBlocking {
        listOf(GateResult.WINDOW_CLOSED, GateResult.OPTED_OUT, GateResult.COLLECTION_ENDED).forEach { gate ->
            var ran = false

            val decision = executor(gate).execute(JobName.CAPTURE, 0) { ran = true; JobOutcome.Success() }

            assertEquals(WorkDecision.SUCCESS, decision)
            assertFalse(ran)
        }
        assertEquals(3, cancelCount)
    }

    @Test
    fun `success is recorded with the standby bucket`() = runBlocking {
        val decision = executor().execute(JobName.CAPTURE, 0) { JobOutcome.Success() }

        assertEquals(WorkDecision.SUCCESS, decision)
        assertEquals(JobState().afterSuccess(now, 10), store.get(JobName.CAPTURE))
    }

    @Test
    fun `a transient failure with attempts left retries without counting as a failed run`() = runBlocking {
        val decision = executor().execute(JobName.SYNC, 0) { failed(FailureKind.TRANSIENT, "offline") }

        assertEquals(WorkDecision.RETRY, decision)
        val state = store.get(JobName.SYNC)
        assertEquals(0, state.consecutiveFailedRuns)
        assertEquals(FailureKind.TRANSIENT, state.lastFailure?.kind)
        assertEquals(now, state.lastAttemptAt)
    }

    @Test
    fun `a transient failure on the last attempt gives up and counts one failed run`() = runBlocking {
        val decision = executor().execute(JobName.SYNC, 4) { failed(FailureKind.TRANSIENT) }

        assertEquals(WorkDecision.FAILURE, decision)
        val state = store.get(JobName.SYNC)
        assertEquals(1, state.consecutiveFailedRuns)
        assertEquals(now, state.firstFailureAt)
    }

    @Test
    fun `permanent failures never retry and accumulate across runs`() = runBlocking {
        val executor = executor()

        assertEquals(WorkDecision.FAILURE, executor.execute(JobName.SYNC, 0) { failed(FailureKind.PERMANENT) })
        assertEquals(WorkDecision.FAILURE, executor.execute(JobName.SYNC, 0) { failed(FailureKind.PERMANENT) })

        assertEquals(2, store.get(JobName.SYNC).consecutiveFailedRuns)
    }

    @Test
    fun `a later success resets the failure history`() = runBlocking {
        val executor = executor()
        executor.execute(JobName.SYNC, 0) { failed(FailureKind.PERMANENT) }

        executor.execute(JobName.SYNC, 0) { JobOutcome.Success() }

        val state = store.get(JobName.SYNC)
        assertEquals(0, state.consecutiveFailedRuns)
        assertNull(state.lastFailure)
    }

    @Test
    fun `study over ends the run, cancels everything and is not counted as a failure`() = runBlocking {
        val decision = executor().execute(JobName.SYNC, 0) { failed(FailureKind.STUDY_OVER, "window ended") }

        assertEquals(WorkDecision.SUCCESS, decision)
        assertEquals(1, cancelCount)
        val state = store.get(JobName.SYNC)
        assertEquals(0, state.consecutiveFailedRuns)
        assertEquals(FailureKind.STUDY_OVER, state.lastFailure?.kind)
    }

    @Test
    fun `an unexpected exception is treated as a transient failure`() = runBlocking {
        val decision = executor().execute(JobName.CAPTURE, 0) { throw IllegalStateException("db locked") }

        assertEquals(WorkDecision.RETRY, decision)
        val message = store.get(JobName.CAPTURE).lastFailure!!.message
        assertTrue(message, message.contains("IllegalStateException"))
        assertTrue(message, message.contains("db locked"))
    }

    @Test
    fun `cancellation is never swallowed`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { executor().execute(JobName.CAPTURE, 0) { throw CancellationException("stop") } }
        }
        assertEquals(JobState(), runBlocking { store.get(JobName.CAPTURE) })
    }

    @Test
    fun `each attempt is logged`() = runBlocking {
        executor().execute(JobName.SYNC, 0) { JobOutcome.Success() }

        assertTrue(logs.joinToString("\n"), logs.any { it.contains("sync") && it.contains("SUCCESS") })
    }

    @Test
    fun `a closed window runs the ending before cancelling`() = runBlocking {
        executor(GateResult.WINDOW_CLOSED).execute(JobName.CAPTURE, 0) { JobOutcome.Success() }

        assertEquals(listOf("ended", "cancel"), events)
    }

    @Test
    fun `an opt-out, a missing session or an already ended collection never runs the ending`() = runBlocking {
        executor(GateResult.OPTED_OUT).execute(JobName.CAPTURE, 0) { JobOutcome.Success() }
        executor(GateResult.NO_SESSION).execute(JobName.CAPTURE, 0) { JobOutcome.Success() }
        executor(GateResult.COLLECTION_ENDED).execute(JobName.CAPTURE, 0) { JobOutcome.Success() }

        assertEquals(listOf("cancel", "cancel"), events)
    }

    @Test
    fun `study over from the backend runs the ending before cancelling`() = runBlocking {
        executor().execute(JobName.SYNC, 0) { failed(FailureKind.STUDY_OVER, "window ended") }

        assertEquals(listOf("ended", "cancel"), events)
    }

    @Test
    fun `a failing ending still cancels every job`() = runBlocking {
        executor(GateResult.WINDOW_CLOSED, onStudyEnded = { throw IllegalStateException("boom") })
            .execute(JobName.CAPTURE, 0) { JobOutcome.Success() }

        assertEquals(1, cancelCount)
    }

    @Test
    fun `cancellation during the ending is never swallowed`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                executor(GateResult.WINDOW_CLOSED, onStudyEnded = { throw CancellationException("stop") })
                    .execute(JobName.CAPTURE, 0) { JobOutcome.Success() }
            }
        }
    }
}
