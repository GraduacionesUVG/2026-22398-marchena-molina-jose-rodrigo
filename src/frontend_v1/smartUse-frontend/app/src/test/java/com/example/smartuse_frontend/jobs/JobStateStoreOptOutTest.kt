package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.data.OptOutKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class JobStateStoreOptOutTest {

    @Test
    fun `kind and hard-delete confirmation round-trip, defaulting to unset`() = runTest {
        val store = FakeJobStateStore()
        assertNull(store.optOutKind())
        assertFalse(store.hardDeleteConfirmed())

        store.setOptOutKind(OptOutKind.HARD)
        store.setHardDeleteConfirmed(true)

        assertEquals(OptOutKind.HARD, store.optOutKind())
        assertEquals(true, store.hardDeleteConfirmed())
    }

    @Test
    fun `hasOptedOut reflects optedOutAt`() = runTest {
        val store = FakeJobStateStore()
        assertEquals(false, store.hasOptedOut.first())

        store.setOptedOutAt(Instant.parse("2026-09-22T10:00:00Z"))

        assertEquals(true, store.hasOptedOut.first())
    }

    @Test
    fun `clearing job history and notifications keeps the opt-out fields`() = runTest {
        val store = FakeJobStateStore()
        store.put(JobName.CAPTURE, JobState(consecutiveFailedRuns = 2))
        store.setOptedOutAt(Instant.parse("2026-09-22T10:00:00Z"))
        store.setOptOutKind(OptOutKind.SOFT)

        store.clearJobHistoryAndNotifications()

        assertEquals(JobState(), store.get(JobName.CAPTURE))
        assertEquals(OptOutKind.SOFT, store.optOutKind())
        assertEquals(Instant.parse("2026-09-22T10:00:00Z"), store.optedOutAt())
    }
}
