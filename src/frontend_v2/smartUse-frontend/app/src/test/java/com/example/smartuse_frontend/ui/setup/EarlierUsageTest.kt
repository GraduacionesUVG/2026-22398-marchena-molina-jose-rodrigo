package com.example.smartuse_frontend.ui.setup

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class EarlierUsageTest {

    private val guatemala = ZoneId.of("America/Guatemala")
    private val now = ZonedDateTime.of(2026, 10, 21, 0, 30, 0, 0, guatemala).toInstant()
    private val start = historyStart(now, guatemala)
    private val end = windowEnd(now, guatemala)
    private val own = "com.example.smartuse_deployment"

    @Test
    fun `window is midnight three days back`() {
        assertEquals(ZonedDateTime.of(2026, 10, 18, 0, 0, 0, 0, guatemala).toInstant(), start)
        assertEquals(ZonedDateTime.of(2026, 10, 21, 0, 0, 0, 0, guatemala).toInstant(), end)
    }

    @Test
    fun `a DST day counts local midnights, not 72 h`() {
        val ny = ZoneId.of("America/New_York")
        val noon = ZonedDateTime.of(2026, 3, 10, 12, 0, 0, 0, ny).toInstant()
        assertEquals(ZonedDateTime.of(2026, 3, 7, 0, 0, 0, 0, ny).toInstant(), historyStart(noon, ny))
    }

    private fun at(t: Instant, type: Int, pkg: String? = null) = RawEvent(t.toEpochMilli(), type, pkg)
    private val inside = start.plusSeconds(3600)

    @Test
    fun `only our own app is no history`() =
        assertFalse(hasEarlierUsage(sequenceOf(at(inside, UsageEvents.Event.MOVE_TO_FOREGROUND, own)), start, end, own))

    @Test
    fun `a screen event in the window is history`() =
        assertTrue(hasEarlierUsage(sequenceOf(at(inside, UsageEvents.Event.SCREEN_INTERACTIVE)), start, end, own))

    @Test
    fun `another app in the window is history`() =
        assertTrue(hasEarlierUsage(sequenceOf(at(inside, UsageEvents.Event.MOVE_TO_FOREGROUND, "com.android.chrome")), start, end, own))

    @Test
    fun `events before the window or on the consent day are not`() {
        val events = sequenceOf(
            at(start.minusSeconds(1), UsageEvents.Event.SCREEN_INTERACTIVE),
            at(end, UsageEvents.Event.SCREEN_INTERACTIVE),
        )
        assertFalse(hasEarlierUsage(events, start, end, own))
    }

    @Test
    fun `an event outside the vocabulary is not`() =
        assertFalse(hasEarlierUsage(sequenceOf(at(inside, UsageEvents.Event.KEYGUARD_HIDDEN)), start, end, own))
}
