package com.example.smartuse_frontend.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnterForegroundTest {

    private val events = mutableListOf<String>()

    @Test
    fun `a refused foreground start (sticky restart without the battery exemption) stops the service instead of crashing`() {
        val entered = enterForeground(
            startForeground = { throw IllegalStateException("mAllowStartForeground false") },
            stopSelf = { events += "stop" },
            logger = { events += "log" },
        )

        assertFalse(entered)
        assertEquals(listOf("log", "stop"), events)
    }

    @Test
    fun `an allowed start goes to the foreground and keeps the service`() {
        val entered = enterForeground(
            startForeground = { events += "foreground" },
            stopSelf = { events += "stop" },
            logger = {},
        )

        assertTrue(entered)
        assertEquals(listOf("foreground"), events)
    }
}
