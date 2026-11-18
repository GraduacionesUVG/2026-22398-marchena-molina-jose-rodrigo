package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.network.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Test

class JobResultPolicyTest {

    private fun failed(kind: FailureKind) = JobOutcome.Failed(kind, "boom")

    @Test
    fun `success is success`() {
        assertEquals(WorkDecision.SUCCESS, JobResultPolicy.decide(JobOutcome.Success(), 0))
    }

    @Test
    fun `transient failures retry until the attempts run out`() {
        // maxAttempts = 5 -> attempts 0..3 retry, the 5th (index 4) gives up.
        (0..3).forEach {
            assertEquals("attempt $it", WorkDecision.RETRY, JobResultPolicy.decide(failed(FailureKind.TRANSIENT), it, maxAttempts = 5))
        }
        assertEquals(WorkDecision.FAILURE, JobResultPolicy.decide(failed(FailureKind.TRANSIENT), 4, maxAttempts = 5))
        assertEquals(WorkDecision.FAILURE, JobResultPolicy.decide(failed(FailureKind.TRANSIENT), 9, maxAttempts = 5))
    }

    @Test
    fun `the default limit is the spec value`() {
        assertEquals(WorkDecision.RETRY, JobResultPolicy.decide(failed(FailureKind.TRANSIENT), JobLimits.MAX_ATTEMPTS_PER_RUN - 2))
        assertEquals(WorkDecision.FAILURE, JobResultPolicy.decide(failed(FailureKind.TRANSIENT), JobLimits.MAX_ATTEMPTS_PER_RUN - 1))
    }

    @Test
    fun `permanent failures never retry`() {
        assertEquals(WorkDecision.FAILURE, JobResultPolicy.decide(failed(FailureKind.PERMANENT), 0))
    }

    @Test
    fun `study over ends the run successfully`() {
        assertEquals(WorkDecision.SUCCESS, JobResultPolicy.decide(failed(FailureKind.STUDY_OVER), 0))
    }
}
