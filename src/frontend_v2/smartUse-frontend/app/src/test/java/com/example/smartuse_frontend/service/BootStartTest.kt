package com.example.smartuse_frontend.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BootStartTest {

    private val events = mutableListOf<String>()

    private fun boot(mode: ServiceMode) = BootStart(
        serviceMode = { mode },
        startService = { events += "service" },
        ensureScheduled = { events += "schedule" },
        ensureEnding = { events += "ending" },
    )

    @Test
    fun `a live study gets its service and schedule back`() = runBlocking {
        boot(ServiceMode.STUDY).run()
        assertEquals(listOf("service", "schedule", "ending"), events)
    }

    @Test
    fun `after the end the service comes back in its mode, without the cycle`() = runBlocking {
        boot(ServiceMode.FORMS_REMINDER).run()
        boot(ServiceMode.SENDING).run()
        assertEquals(listOf("service", "ending", "service", "ending"), events)
    }

    @Test
    fun `before setup or after the send only the ending is ensured`() = runBlocking {
        boot(ServiceMode.OFF).run()
        assertEquals(listOf("ending"), events)
    }
}