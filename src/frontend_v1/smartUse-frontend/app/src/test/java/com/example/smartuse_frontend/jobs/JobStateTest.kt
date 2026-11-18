package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.network.FailureKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class JobStateTest {

    private val t1 = Instant.parse("2026-09-20T10:00:00Z")
    private val t2 = Instant.parse("2026-09-20T16:00:00Z")
    private val t3 = Instant.parse("2026-09-20T22:00:00Z")
    private val failure = JobFailure(FailureKind.PERMANENT, "boom")

    @Test
    fun `a fresh state has no history and is not stuck`() {
        val state = JobState()

        assertNull(state.lastSuccessAt)
        assertEquals(0, state.consecutiveFailedRuns)
        assertFalse(state.isStuck(threshold = 3))
    }

    @Test
    fun `failed runs are counted and firstFailureAt is set once`() {
        val state = JobState().afterFailedRun(t1, failure, 10).afterFailedRun(t2, failure, 10)

        assertEquals(2, state.consecutiveFailedRuns)
        assertEquals(t1, state.firstFailureAt)
        assertEquals(t2, state.lastAttemptAt)
        assertEquals(failure, state.lastFailure)
    }

    @Test
    fun `an attempt that will retry is not a failed run`() {
        val state = JobState().afterAttempt(t1, JobFailure(FailureKind.TRANSIENT, "offline"), 20)

        assertEquals(0, state.consecutiveFailedRuns)
        assertNull(state.firstFailureAt)
        assertEquals(t1, state.lastAttemptAt)
        assertEquals("offline", state.lastFailure?.message)
        assertEquals(20, state.lastStandbyBucket)
    }

    @Test
    fun `success resets the failure history and records the bucket`() {
        val state = JobState()
            .afterFailedRun(t1, failure, 10)
            .afterFailedRun(t2, failure, 10)
            .afterSuccess(t3, 20)

        assertEquals(0, state.consecutiveFailedRuns)
        assertNull(state.firstFailureAt)
        assertNull(state.lastFailure)
        assertEquals(t3, state.lastSuccessAt)
        assertEquals(t3, state.lastAttemptAt)
        assertEquals(20, state.lastStandbyBucket)
    }

    @Test
    fun `stuck means at least the threshold of failed runs in a row`() {
        val two = JobState().afterFailedRun(t1, failure, null).afterFailedRun(t2, failure, null)
        val three = two.afterFailedRun(t3, failure, null)

        assertFalse(two.isStuck(3))
        assertTrue(three.isStuck(3))
    }

    @Test
    fun `health reports stuckSince only when stuck`() {
        val healthy = JobState().afterSuccess(t1, null).toHealth(3)
        assertEquals(JobHealth(lastSuccessAt = t1, stuckSince = null), healthy)

        val stuck = JobState().afterSuccess(t1, null)
            .afterFailedRun(t1, failure, null).afterFailedRun(t2, failure, null).afterFailedRun(t3, failure, null)
            .toHealth(3)
        assertEquals(JobHealth(lastSuccessAt = t1, stuckSince = t1), stuck)
    }

    @Test
    fun `the fake store round-trips per-job state and the opt-out flag`() = runBlocking {
        val store = FakeJobStateStore()
        assertEquals(JobState(), store.get(JobName.CAPTURE))
        assertNull(store.optedOutAt())

        store.put(JobName.CAPTURE, JobState().afterSuccess(t1, 10))
        store.setOptedOutAt(t2)

        assertEquals(t1, store.get(JobName.CAPTURE).lastSuccessAt)
        assertEquals(JobState(), store.get(JobName.SYNC))
        assertEquals(t2, store.optedOutAt())
    }
}
