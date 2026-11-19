package com.example.smartuse_frontend.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class FiringRulesTest {

    private val zone = ZoneId.of("America/Guatemala")
    private val t10 = Instant.parse("2026-10-20T16:00:00Z") // 10:00 local

    private fun scores(f: Double, s: Double) = Scores(f = f, s = s, fCutoff = 0.4, sCutoff = 0.2)

    @Test
    fun `fires only above its own cutoff`() {
        assertNull(choosePattern(scores(0.4, 0.2), t10, zone, FiringState()))
        assertEquals(Pattern.F, choosePattern(scores(0.41, 0.2), t10, zone, FiringState()))
        assertEquals(Pattern.S, choosePattern(scores(0.4, 0.21), t10, zone, FiringState()))
    }

    @Test
    fun `larger margin wins`() {
        assertEquals(Pattern.S, choosePattern(scores(0.45, 0.3), t10, zone, FiringState()))
        assertEquals(Pattern.F, choosePattern(scores(0.6, 0.3), t10, zone, FiringState()))
    }

    @Test
    fun `tie goes to F`() {
        assertEquals(Pattern.F, choosePattern(scores(0.5, 0.3), t10, zone, FiringState()))
    }

    @Test
    fun `cooldown blocks either pattern for 10 min`() {
        val state = FiringState().afterShown(Pattern.F, t10, zone)
        assertNull(choosePattern(scores(0.1, 0.9), t10.plusSeconds(599), zone, state))
        assertEquals(Pattern.S, choosePattern(scores(0.1, 0.9), t10.plusSeconds(600), zone, state))
    }

    @Test
    fun `episode blocks only the same pattern for 60 min`() {
        val state = FiringState().afterShown(Pattern.F, t10, zone)
        assertNull(choosePattern(scores(0.9, 0.1), t10.plusSeconds(3599), zone, state))
        assertEquals(Pattern.F, choosePattern(scores(0.9, 0.1), t10.plusSeconds(3600), zone, state))
    }

    @Test
    fun `blocked winner gives way to the other`() {
        val state = FiringState().afterShown(Pattern.F, t10, zone)
        // 10:40: both fire, F by more, but F is inside its hour.
        assertEquals(Pattern.S, choosePattern(scores(0.9, 0.25), t10.plusSeconds(2400), zone, state))
    }

    @Test
    fun `daily cap of 20`() {
        var state = FiringState()
        var now = t10.minusSeconds(9 * 3600) // 01:00 local
        repeat(20) {
            state = state.afterShown(if (it % 2 == 0) Pattern.F else Pattern.S, now, zone)
            now = now.plusSeconds(3600)
        }
        assertEquals(20, state.shownToday)
        assertNull(choosePattern(scores(0.9, 0.9), now, zone, state))
    }

    @Test
    fun `daily count resets on a new local day`() {
        val lateNight = Instant.parse("2026-10-21T05:50:00Z") // 23:50 local, 20 Oct
        val state = FiringState(shownDay = LocalDate.of(2026, 10, 20), shownToday = 20).afterShown(Pattern.F, lateNight.minusSeconds(4000), zone)
        val afterMidnight = lateNight.plusSeconds(20 * 60) // 00:10 local, 21 Oct
        assertEquals(Pattern.S, choosePattern(scores(0.1, 0.9), afterMidnight, zone, state))
        assertEquals(1, state.afterShown(Pattern.S, afterMidnight, zone).shownToday)
    }

    @Test
    fun `afterShown sets that pattern's time and counts`() {
        val state = FiringState().afterShown(Pattern.S, t10, zone)
        assertEquals(FiringState(lastShownF = null, lastShownS = t10, shownDay = LocalDate.of(2026, 10, 20), shownToday = 1), state)
        assertEquals(2, state.afterShown(Pattern.F, t10.plusSeconds(700), zone).shownToday)
    }
}
