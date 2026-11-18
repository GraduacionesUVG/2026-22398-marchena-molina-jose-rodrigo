package com.example.smartuse_frontend.service

import com.example.smartuse_frontend.metrics.DecisionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionSchedulerTest {

    private class Timer(val atMs: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private val timers = mutableListOf<Timer>()
    private val points = mutableListOf<Pair<DecisionKind, Long>>()
    private val scheduler = DecisionScheduler(
        schedule = { atMs, action -> Timer(atMs, action).also { timers += it }.let { t -> Cancellable { t.cancelled = true } } },
        onPoint = { kind, t -> points += kind to t },
    )

    private val pending get() = timers.filter { !it.cancelled }

    /** Fires the one pending timer, as the Handler would at its time. */
    private fun fire(): Long {
        val timer = pending.single()
        timer.cancelled = true
        timer.action()
        return timer.atMs
    }

    private val min = 60_000L

    @Test
    fun `unlock after a new screen-on fires screen_on at the screen-on time`() {
        scheduler.onScreenOn(1_000_000)
        scheduler.onUserPresent(1_004_000)
        assertEquals(listOf(DecisionKind.SCREEN_ON to 1_000_000L), points)
        assertEquals(1_000_000 + 10 * min, pending.single().atMs)
    }

    @Test
    fun `screen-on less than 30 s after screen-off makes no point`() {
        scheduler.onScreenOn(1_000_000)
        scheduler.onUserPresent(1_000_500)
        scheduler.onScreenOff(1_100_000)
        scheduler.onScreenOn(1_129_999)
        scheduler.onUserPresent(1_130_500)
        assertEquals(listOf(DecisionKind.SCREEN_ON to 1_000_000L), points)
    }

    @Test
    fun `screen-on exactly 30 s after screen-off is a new session`() {
        scheduler.onScreenOn(1_000_000)
        scheduler.onUserPresent(1_000_500)
        scheduler.onScreenOff(1_100_000)
        scheduler.onScreenOn(1_130_000)
        scheduler.onUserPresent(1_130_500)
        assertEquals(listOf(DecisionKind.SCREEN_ON to 1_000_000L, DecisionKind.SCREEN_ON to 1_130_000L), points)
    }

    @Test
    fun `active_use every 10 min from t0`() {
        scheduler.onScreenOn(0)
        scheduler.onUserPresent(3_000)
        assertEquals(10 * min, fire())
        assertEquals(20 * min, fire())
        assertEquals(
            listOf(DecisionKind.SCREEN_ON to 0L, DecisionKind.ACTIVE_USE to 10 * min, DecisionKind.ACTIVE_USE to 20 * min),
            points,
        )
    }

    @Test
    fun `no active_use at or after 3 h`() {
        scheduler.onScreenOn(0)
        scheduler.onUserPresent(1_000)
        repeat(17) { fire() }
        assertEquals(170 * min, points.last().second)
        assertTrue("nothing armed for 180 min", pending.isEmpty())
        assertEquals(18, points.size)
    }

    @Test
    fun `screen off cancels the timer`() {
        scheduler.onScreenOn(0)
        scheduler.onUserPresent(1_000)
        scheduler.onScreenOff(5 * min)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `continued session re-arms at the next k-multiple after the gap, once unlocked`() {
        scheduler.onScreenOn(0)
        scheduler.onUserPresent(1_000)
        scheduler.onScreenOff(10 * min - 10_000)
        scheduler.onScreenOn(10 * min + 10_000) // 20 s off: same session; the 10-min point fell in the gap
        assertTrue("nothing armed before the unlock", pending.isEmpty())
        scheduler.onUserPresent(10 * min + 11_000)
        assertEquals(20 * min, pending.single().atMs)
        assertEquals(listOf(DecisionKind.SCREEN_ON to 0L), points)
    }

    @Test
    fun `a lock-screen glance within 30 s after a session arms nothing`() {
        scheduler.onScreenOn(0)
        scheduler.onUserPresent(1_000)
        scheduler.onScreenOff(9 * min)
        scheduler.onScreenOn(9 * min + 10_000) // never unlocked
        assertTrue(pending.isEmpty())
        scheduler.onScreenOff(9 * min + 15_000)
        assertEquals(listOf(DecisionKind.SCREEN_ON to 0L), points)
    }

    @Test
    fun `lock-screen glances do not move the session gap`() {
        scheduler.onScreenOn(0)
        scheduler.onUserPresent(1_000)
        scheduler.onScreenOff(60_000)
        scheduler.onScreenOn(100_000) // glance, 40 s after the session
        scheduler.onScreenOff(105_000)
        scheduler.onScreenOn(115_000) // 55 s after the session: a new one, despite the glance 10 s ago
        scheduler.onUserPresent(116_000)
        assertEquals(listOf(DecisionKind.SCREEN_ON to 0L, DecisionKind.SCREEN_ON to 115_000L), points)
    }

    @Test
    fun `a glance just after a session does not let a later screen-on continue it`() {
        scheduler.onScreenOn(0)
        scheduler.onUserPresent(1_000)
        scheduler.onScreenOff(60_000)
        scheduler.onScreenOn(70_000) // glance 10 s after: would continue, but never unlocked
        scheduler.onScreenOff(75_000)
        scheduler.onScreenOn(95_000) // 35 s after the session ended
        scheduler.onUserPresent(96_000)
        assertEquals(listOf(DecisionKind.SCREEN_ON to 0L, DecisionKind.SCREEN_ON to 95_000L), points)
    }

    @Test
    fun `screen-on without unlock makes no point`() {
        scheduler.onScreenOn(0)
        scheduler.onScreenOff(20_000)
        scheduler.onScreenOn(5 * min)
        assertEquals(emptyList<Pair<DecisionKind, Long>>(), points)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `a glance before the unlocked screen-on is not the session start`() {
        scheduler.onScreenOn(0) // never unlocked: 0 apps, non-use in training
        scheduler.onScreenOff(5_000)
        scheduler.onScreenOn(15_000)
        scheduler.onUserPresent(16_000)
        assertEquals(listOf(DecisionKind.SCREEN_ON to 15_000L), points)
    }

    @Test
    fun `no points when the service starts mid-session`() {
        scheduler.onUserPresent(1_000)
        assertEquals(emptyList<Pair<DecisionKind, Long>>(), points)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `a second unlock in the same session makes no new point`() {
        scheduler.onScreenOn(0)
        scheduler.onUserPresent(1_000)
        scheduler.onUserPresent(2_000)
        assertEquals(1, points.size)
        assertEquals(1, pending.size)
    }

    @Test
    fun `stop cancels and forgets`() {
        scheduler.onScreenOn(0)
        scheduler.onUserPresent(1_000)
        scheduler.stop()
        assertTrue(pending.isEmpty())
        scheduler.onScreenOn(10_000) // no screen-off remembered: a new session
        scheduler.onUserPresent(11_000)
        assertEquals(listOf(DecisionKind.SCREEN_ON to 0L, DecisionKind.SCREEN_ON to 10_000L), points)
    }
}
