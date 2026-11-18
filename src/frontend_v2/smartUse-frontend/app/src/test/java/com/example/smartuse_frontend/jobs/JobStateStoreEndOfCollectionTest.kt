package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.study.EndMarks
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
    fun `endMarks follows every mark`() = runTest {
        val store = FakeJobStateStore()
        val t = Instant.parse("2026-11-01T12:00:00Z")
        assertEquals(EndMarks(), store.endMarks.first())

        store.setOptedOutAt(t)
        store.setFormsCompletedAt(t)
        store.setFinalSentAt(t)
        store.setEndCleanupDoneAt(t)

        assertEquals(EndMarks(optedOutAt = t, formsCompletedAt = t, finalSentAt = t), store.endMarks.first())
        assertEquals(store.endMarks.first(), store.readEndMarks())
        assertEquals(t, store.endCleanupDoneAt())
    }
}
