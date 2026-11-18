package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.data.db.CanonicalSyncOutcome
import com.example.smartuse_frontend.network.FailureKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncJobTest {

    @Test
    fun `a successful sync maps to success and carries the note`() = runBlocking {
        val outcome = SyncJob {
            CanonicalSyncOutcome.Success(10, null, null, note = "post-check failed", batches = 1)
        }.body()

        assertEquals(JobOutcome.Success(note = "post-check failed"), outcome)
    }

    @Test
    fun `a failed sync keeps its kind and message`() = runBlocking {
        val outcome = SyncJob {
            CanonicalSyncOutcome.Failed("HTTP 409", FailureKind.PERMANENT, recordsSentBeforeFailure = 500)
        }.body()

        assertEquals(JobOutcome.Failed(FailureKind.PERMANENT, "HTTP 409"), outcome)
    }
}
