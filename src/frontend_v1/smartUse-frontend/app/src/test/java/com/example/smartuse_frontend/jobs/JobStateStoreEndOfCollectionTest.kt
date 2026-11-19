package com.example.smartuse_frontend.jobs

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class JobStateStoreEndOfCollectionTest {

    @Test
    fun `the cutoff date round-trips and null clears it`() = runTest {
        val store = FakeJobStateStore()
        assertNull(store.cutoffDate())

        store.setCutoffDate(LocalDate.of(2026, 12, 1))
        assertEquals(LocalDate.of(2026, 12, 1), store.cutoffDate())

        store.setCutoffDate(null)
        assertNull(store.cutoffDate())
    }

    @Test
    fun `collectionEnded reflects collectionEndedAt`() = runTest {
        val store = FakeJobStateStore()
        assertEquals(false, store.collectionEnded.first())

        store.setCollectionEndedAt(Instant.parse("2026-12-01T06:00:00Z"))

        assertEquals(Instant.parse("2026-12-01T06:00:00Z"), store.collectionEndedAt())
        assertEquals(true, store.collectionEnded.first())
    }

    @Test
    fun `clearing job history keeps the cutoff and the end`() = runTest {
        val store = FakeJobStateStore()
        store.put(JobName.SYNC, JobState(consecutiveFailedRuns = 1))
        store.setCutoffDate(LocalDate.of(2026, 12, 1))
        store.setCollectionEndedAt(Instant.parse("2026-12-01T06:00:00Z"))

        store.clearJobHistoryAndNotifications()

        assertEquals(JobState(), store.get(JobName.SYNC))
        assertEquals(LocalDate.of(2026, 12, 1), store.cutoffDate())
        assertEquals(Instant.parse("2026-12-01T06:00:00Z"), store.collectionEndedAt())
    }
}
