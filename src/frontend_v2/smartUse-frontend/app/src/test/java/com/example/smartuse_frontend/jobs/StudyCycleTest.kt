package com.example.smartuse_frontend.jobs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyCycleTest {

    private val events = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    private fun cycle(
        live: Boolean = true,
        capture: suspend () -> WorkDecision = { WorkDecision.SUCCESS },
        freeze: suspend () -> Unit = {},
    ) = StudyCycle(
        isStudyLive = { live },
        startService = { events += "service" },
        runCapture = { events += "capture"; capture() },
        freezeProfiles = { events += "freeze"; freeze() },
        logger = { logs += it },
    )

    @Test
    fun `capture then freeze`() = runBlocking {
        assertEquals(WorkDecision.SUCCESS, cycle().run())
        assertEquals(listOf("service", "capture", "freeze"), events)
    }

    @Test
    fun `not live - no service start`() = runBlocking {
        cycle(live = false).run()
        assertEquals(listOf("capture", "freeze"), events)
    }

    @Test
    fun `the cycle's result is capture's`() = runBlocking {
        assertEquals(WorkDecision.RETRY, cycle(capture = { WorkDecision.RETRY }).run())
        assertEquals(WorkDecision.FAILURE, cycle(capture = { WorkDecision.FAILURE }).run())
    }

    @Test
    fun `freeze failure does not change the result`() = runBlocking {
        assertEquals(WorkDecision.SUCCESS, cycle(freeze = { error("disk") }).run())
        assertTrue(logs.any { it.startsWith("cycle: profile freeze failed") })
    }

    @Test
    fun `a cancelled cycle propagates`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { cycle(capture = { throw CancellationException("stopped") }).run() }
        }
    }
}
