package com.example.smartuse_frontend.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeOptOutRepositoryTest {

    @Test
    fun `status starts as not opted out`() = runTest {
        assertEquals(OptOutStatus.NotOptedOut, FakeOptOutRepository().status())
    }

    @Test
    fun `soft opt-out moves status to SoftDone`() = runTest {
        val repo = FakeOptOutRepository()

        val result = repo.optOutSoft()

        assertEquals(OptOutResult.Success, result)
        assertEquals(OptOutStatus.SoftDone, repo.status())
    }

    @Test
    fun `a successful hard opt-out confirms the delete`() = runTest {
        val repo = FakeOptOutRepository(shouldSucceed = true)

        repo.optOutHard()

        assertEquals(OptOutStatus.Hard(deleteConfirmed = true, attemptFailed = false), repo.status())
    }

    @Test
    fun `a failed hard opt-out records an error and does not confirm`() = runTest {
        val repo = FakeOptOutRepository(shouldSucceed = false)

        repo.optOutHard()

        val status = repo.status() as OptOutStatus.Hard
        assertEquals(false, status.deleteConfirmed)
        assertEquals(true, status.attemptFailed)
    }
}
