package com.example.smartuse_frontend.ui.welcome

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class InterventionsStartTest {

    @Test
    fun `rounds up to the quarter hour`() =
        assertEquals(Instant.parse("2026-10-24T10:30:00Z"), interventionsStartAt(Instant.parse("2026-10-21T10:07:00Z"), 15))

    @Test
    fun `an exact quarter stays`() =
        assertEquals(Instant.parse("2026-10-24T10:15:00Z"), interventionsStartAt(Instant.parse("2026-10-21T10:00:00Z"), 15))

    @Test
    fun `the line in the device zone`() = assertEquals(
        "Las intervenciones visuales comenzarán a más tardar el sábado 24 de octubre a las 14:30.",
        interventionsStartLine(Instant.parse("2026-10-24T20:30:00Z"), ZoneId.of("America/Guatemala")),
    )
}
