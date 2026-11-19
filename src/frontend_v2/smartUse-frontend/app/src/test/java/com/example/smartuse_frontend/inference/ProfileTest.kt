package com.example.smartuse_frontend.inference

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ProfileTest {

    private fun close(expected: Double, actual: Double) = abs(expected - actual) <= 1e-5 * maxOf(1.0, abs(expected))

    private fun assertClose(at: String, expected: Double, actual: Double) =
        assertTrue("$at: expected $expected, got $actual", close(expected, actual))

    private fun assertClose(at: String, expected: List<Double>, actual: List<Double>) {
        assertEquals("$at size", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { i, (e, a) -> assertClose("$at[$i]", e, a) }
    }

    @Test
    fun `fixture profiles`() {
        for (person in Fixtures.people) {
            val epochs = Fixtures.epochs(person)
            val sessions = buildSessions(epochs)
            for (pattern in Pattern.entries) {
                val at = "$person ${pattern.name}"
                val expected = Json.decodeFromString(Profile.serializer(), Fixtures.profileJson(person, pattern).toString())
                val got = buildProfile(pattern, epochs, sessions, Fixtures.OFFSET_S)!!
                assertClose("$at pf", expected.pf, got.pf)
                assertClose("$at ps", expected.ps, got.ps)
                assertClose("$at f_q", expected.fQ, got.fQ)
                assertClose("$at s_q", expected.sQ, got.sQ)
                assertClose("$at f_median", expected.fMedian, got.fMedian)
                assertClose("$at s_median", expected.sMedian, got.sMedian)
                assertClose("$at freq_day", expected.freqDay, got.freqDay)
                assertClose("$at use_day_min", expected.useDayMin, got.useDayMin)
                assertClose("$at logdur_median", expected.logdurMedian, got.logdurMedian)
                assertClose("$at nonuse_day_min", expected.nonuseDayMin, got.nonuseDayMin)
                assertClose("$at lognonuse_median", expected.lognonuseMedian, got.lognonuseMedian)
                assertClose("$at sessions_by_hour", expected.sessionsByHour, got.sessionsByHour)
                assertEquals("$at profile_days", expected.profileDays, got.profileDays)
                assertEquals("$at offset_s", expected.offsetS, got.offsetS, 0.0)
                assertEquals("$at n_profile_points", expected.nProfilePoints, got.nProfilePoints)
            }
        }
    }

    @Test
    fun `quantile type 7`() {
        assertEquals(1.75, quantile7(listOf(1.0, 2.0, 3.0, 4.0), 0.25), 1e-12)
        assertEquals(4.0, quantile7(listOf(1.0, 2.0, 3.0, 4.0), 1.0), 0.0)
        assertEquals(5.0, quantile7(listOf(5.0), 0.8), 0.0)
    }

    @Test
    fun `fewer than 3 usable days gives null`() {
        // Two days of hourly sessions: sufficient days, but only two of them.
        val epochs = buildList {
            var t = 0.0
            while (t < 2 * 86_400.0) {
                add(ModelEpoch(t, t + 60, true, 1))
                add(ModelEpoch(t + 60, t + 3600, false, 0))
                t += 3600
            }
        }
        assertNull(buildProfile(Pattern.F, epochs, buildSessions(epochs), 0.0))
    }

    @Test
    fun `ready an hour after the third day ends`() {
        // Local days 10, 11, 12 at UTC−6: day 12 ends at 13 · 86400 + 6 h UTC.
        assertEquals(13 * 86_400.0 + 6 * 3600.0 + 3600.0, profileReadyAt(listOf(10L, 11L, 12L), -6 * 3600.0), 0.0)
    }

    @Test
    fun `round trip through json`() {
        val p = Json.decodeFromString(Profile.serializer(), Fixtures.profileJson("u40466b", Pattern.F).toString())
        assertEquals(p, Json.decodeFromString(Profile.serializer(), Json.encodeToString(Profile.serializer(), p)))
    }

    @Test
    fun `buildProfile with the 3 profile days equals the 4-arg one`() {
        for (person in Fixtures.people) {
            val epochs = Fixtures.epochs(person)
            val sessions = buildSessions(epochs)
            val days = profileDays(profilePoints(epochs, sessions, Fixtures.OFFSET_S, 0), Fixtures.OFFSET_S)
            for (pattern in Pattern.entries) {
                assertEquals(
                    buildProfile(pattern, epochs, sessions, Fixtures.OFFSET_S),
                    buildProfile(pattern, epochs, sessions, Fixtures.OFFSET_S, days) { it.usable },
                )
            }
        }
    }

    @Test
    fun `buildProfile with one day divides by one`() {
        val epochs = Fixtures.epochs("u40466b")
        val sessions = buildSessions(epochs)
        val day = profileDays(profilePoints(epochs, sessions, Fixtures.OFFSET_S, 0), Fixtures.OFFSET_S).first()
        val p = buildProfile(Pattern.F, epochs, sessions, Fixtures.OFFSET_S, listOf(day)) { it.usable }!!
        val dayStart = day * 86_400.0 - Fixtures.OFFSET_S
        val clipped = sessions.count { it.start < dayStart + 86_400.0 && it.end > dayStart }
        assertEquals(clipped.toDouble(), p.freqDay, 0.0)
        assertEquals(1, p.profileDays.size)
    }

    @Test
    fun `buildProfile with no kept point gives null`() {
        val epochs = Fixtures.epochs("u40466b")
        assertNull(buildProfile(Pattern.F, epochs, buildSessions(epochs), Fixtures.OFFSET_S, listOf(0L)) { it.usable })
    }
}
