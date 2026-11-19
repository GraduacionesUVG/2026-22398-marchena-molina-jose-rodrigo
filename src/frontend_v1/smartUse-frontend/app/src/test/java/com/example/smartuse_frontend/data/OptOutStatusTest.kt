package com.example.smartuse_frontend.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class OptOutStatusTest {

    private val now = Instant.parse("2026-09-22T10:00:00Z")

    @Test
    fun `no opt-out yet is NotOptedOut`() {
        assertEquals(OptOutStatus.NotOptedOut, deriveOptOutStatus(null, null, false, false))
    }

    @Test
    fun `optedOutAt without a kind is treated as not opted out`() {
        assertEquals(OptOutStatus.NotOptedOut, deriveOptOutStatus(now, null, false, false))
    }

    @Test
    fun `soft is always done regardless of the confirmation fields`() {
        assertEquals(OptOutStatus.SoftDone, deriveOptOutStatus(now, OptOutKind.SOFT, false, true))
    }

    @Test
    fun `hard not yet confirmed carries a failed attempt`() {
        assertEquals(
            OptOutStatus.Hard(deleteConfirmed = false, attemptFailed = true),
            deriveOptOutStatus(now, OptOutKind.HARD, false, true),
        )
    }

    @Test
    fun `hard confirmed has no failed attempt`() {
        assertEquals(
            OptOutStatus.Hard(deleteConfirmed = true, attemptFailed = false),
            deriveOptOutStatus(now, OptOutKind.HARD, true, false),
        )
    }
}
