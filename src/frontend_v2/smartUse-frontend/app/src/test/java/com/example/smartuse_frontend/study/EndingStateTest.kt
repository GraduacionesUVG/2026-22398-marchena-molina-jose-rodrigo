package com.example.smartuse_frontend.study

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class EndingStateTest {

    private val t = Instant.parse("2026-11-01T12:00:00Z")

    @Test
    fun `no mark is live`() {
        assertEquals(EndingState.LIVE, EndMarks().state)
    }

    @Test
    fun `either end mark alone or both is ended`() {
        assertEquals(EndingState.ENDED, EndMarks(collectionEndedAt = t).state)
        assertEquals(EndingState.ENDED, EndMarks(optedOutAt = t).state)
        assertEquals(EndingState.ENDED, EndMarks(collectionEndedAt = t, optedOutAt = t).state)
    }

    @Test
    fun `forms completed is forms done, and the final send wins over everything`() {
        assertEquals(EndingState.FORMS_DONE, EndMarks(collectionEndedAt = t, formsCompletedAt = t).state)
        assertEquals(EndingState.SENT, EndMarks(collectionEndedAt = t, formsCompletedAt = t, finalSentAt = t).state)
        assertEquals(EndingState.SENT, EndMarks(finalSentAt = t).state)
    }

    @Test
    fun `the reason is opt_out only with the opt-out mark`() {
        assertEquals(EndReason.COMPLETED, EndMarks(collectionEndedAt = t).reason)
        assertEquals(EndReason.OPT_OUT, EndMarks(optedOutAt = t).reason)
        assertEquals("completed", EndReason.COMPLETED.wire)
        assertEquals("opt_out", EndReason.OPT_OUT.wire)
    }
}