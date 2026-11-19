package com.example.smartuse_frontend.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
