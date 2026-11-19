package com.example.smartuse_frontend.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FakeSetupStateRepositoryTest {

    @Test
    fun `defaults to incomplete`() = runTest {
        val repo = FakeSetupStateRepository()
        assertFalse(repo.isCompleteNow())
        assertFalse(repo.isComplete.first())
    }

    @Test
    fun `markComplete flips the flag and the flow`() = runTest {
        val repo = FakeSetupStateRepository()

        repo.markComplete()

        assertTrue(repo.isCompleteNow())
        assertTrue(repo.isComplete.first())
    }

    @Test
    fun `respects the initial value`() = runTest {
        assertTrue(FakeSetupStateRepository(initial = true).isCompleteNow())
    }

    @Test
    fun `history start is stored and cleared`() = runTest {
        val repo = FakeSetupStateRepository()
        assertNull(repo.historyStart())
        repo.setHistoryStart(Instant.ofEpochSecond(42))
        assertEquals(Instant.ofEpochSecond(42), repo.historyStart())
        repo.setHistoryStart(null)
        assertNull(repo.historyStart())
    }
}
