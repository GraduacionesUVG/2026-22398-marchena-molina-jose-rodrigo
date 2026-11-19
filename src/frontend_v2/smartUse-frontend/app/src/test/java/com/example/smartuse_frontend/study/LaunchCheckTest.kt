package com.example.smartuse_frontend.study

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchCheckTest {

    private val events = mutableListOf<String>()

    /** [open] answers the window checks in order: first from the stored date, then after the fetch. */
    private fun check(vararg open: Boolean, dateChanged: Boolean = false): LaunchCheck {
        val answers = open.toMutableList()
        return LaunchCheck(
            refreshCutoff = { events += "fetch"; dateChanged },
            isWindowOpen = { events += "check"; answers.removeAt(0) },
            endCollection = { events += "end" },
            ensureScheduled = { events += "schedule" },
            startService = { events += "service" },
            refreshNotice = { events += "notice" },
        )
    }

    @Test
    fun `a stored date already past ends collection without scheduling, starting the service or fetching`() = runTest {
        check(false).run()

        assertEquals(listOf("check", "end"), events)
    }

    @Test
    fun `an open window schedules before the fetch, so nothing waits on the network, and starts the service once the window is confirmed`() = runTest {
        check(true, true).run()

        assertEquals(listOf("check", "schedule", "fetch", "check", "service"), events)
    }

    @Test
    fun `a fetched date that closes the window ends collection without ever starting the service`() = runTest {
        check(true, false, dateChanged = true).run()

        assertEquals(listOf("check", "schedule", "fetch", "check", "end"), events)
    }

    @Test
    fun `a newly learned date that keeps the window open re-posts the notice`() = runTest {
        check(true, true, dateChanged = true).run()

        assertEquals(listOf("check", "schedule", "fetch", "check", "service", "notice"), events)
    }
}
