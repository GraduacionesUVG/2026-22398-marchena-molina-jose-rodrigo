package com.example.smartuse_frontend.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class InstantParsingTest {

    @Test
    fun `a stored instant parses, a missing or corrupt one reads as unset`() {
        assertEquals(Instant.parse("2026-12-01T06:00:00Z"), parseInstantOrNull("2026-12-01T06:00:00Z"))
        assertNull(parseInstantOrNull(null))
        assertNull(parseInstantOrNull("not-an-instant"))
    }
}
