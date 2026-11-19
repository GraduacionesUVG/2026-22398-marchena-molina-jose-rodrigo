package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.network.FailureKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureJobTest {

    private val events = mutableListOf<String>()

    private fun job(
        hasAccess: Boolean = true,
        pipeline: suspend () -> Unit = { events += "pipeline" },
        refreshNotice: suspend () -> Unit = { events += "notice" },
    ) = CaptureJob(
        refreshNotice = refreshNotice,
        hasUsageAccess = { hasAccess },
        runPipeline = pipeline,
        enqueueSyncNow = { events += "sync-now" },
        logger = {},
    )

    // queryEvents returns an empty result (not an exception) without usage
    // access, so capture must check explicitly or it would "succeed" empty.
    @Test
    fun `without usage access it refreshes the notification, then fails permanently and touches nothing else`() = runBlocking {
        val outcome = job(hasAccess = false).body()

        assertEquals(JobOutcome.Failed(FailureKind.PERMANENT, CaptureJob.USAGE_ACCESS_REVOKED), outcome)
        assertEquals(listOf("notice"), events)
    }

    @Test
    fun `success runs the pipeline and then requests a sync`() = runBlocking {
        val outcome = job().body()

        assertEquals(JobOutcome.Success(), outcome)
        assertEquals(listOf("notice", "pipeline", "sync-now"), events)
    }

    @Test
    fun `a failing pipeline propagates and does not request a sync`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { job(pipeline = { throw IllegalStateException("db") }).body() }
        }
        assertEquals(listOf("notice"), events)
    }

    @Test
    fun `a SecurityException from the pipeline is a permanent usage-access failure`() = runBlocking {
        val outcome = job(pipeline = { throw SecurityException("no access") }).body()

        outcome as JobOutcome.Failed
        assertEquals(FailureKind.PERMANENT, outcome.kind)
        assertTrue(outcome.message, outcome.message.startsWith(CaptureJob.USAGE_ACCESS_REVOKED))
        assertEquals(listOf("notice"), events)
    }

    @Test
    fun `a failing notification refresh never stops the capture`() = runBlocking {
        val outcome = job(refreshNotice = { throw IllegalStateException("no channel") }).body()

        assertEquals(JobOutcome.Success(), outcome)
        assertEquals(listOf("pipeline", "sync-now"), events)
    }
}
