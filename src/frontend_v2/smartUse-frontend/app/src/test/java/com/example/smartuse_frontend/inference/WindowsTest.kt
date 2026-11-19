package com.example.smartuse_frontend.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class WindowsTest {

    private fun close(expected: Double, actual: Double) = abs(expected - actual) <= 1e-5 * maxOf(1.0, abs(expected))

    private fun checkPoints(labelOffsetMin: Int, file: String) {
        for (person in Fixtures.people) {
            val epochs = Fixtures.epochs(person)
            val points = profilePoints(epochs, buildSessions(epochs), Fixtures.OFFSET_S, labelOffsetMin)
            val expected = Fixtures.csv(person, file)
            assertEquals("$person count", expected.size, points.size)
            expected.zip(points).forEachIndexed { i, (row, p) ->
                val at = "$person $file row $i t=${row["t_utc"]}"
                assertEquals("$at t", Fixtures.utcSeconds(row.getValue("t_utc")), p.t, 1e-3)
                assertEquals("$at is_unlock", Fixtures.bool(row.getValue("is_unlock")), p.isUnlock)
                assertEquals("$at n", row.getValue("n_sessions").toInt(), p.metrics.n)
                for ((name, value) in listOf("span_s" to p.metrics.span, "longest_s" to p.metrics.longest, "f" to p.metrics.f, "s" to p.metrics.s)) {
                    val want = row.getValue(name).toDouble()
                    assertTrue("$at $name: expected $want, got $value", close(want, value))
                }
                assertEquals("$at masked", Fixtures.bool(row.getValue("masked")), p.masked)
                assertEquals("$at censored", Fixtures.bool(row.getValue("censored")), p.censored)
                assertEquals("$at day_ok", Fixtures.bool(row.getValue("day_ok")), p.dayOk)
            }
        }
    }

    @Test
    fun `fixture points b0`() = checkPoints(0, "points_b0.csv")

    @Test
    fun `fixture points b30`() = checkPoints(30, "points_b30.csv")

    private fun session(start: Double, end: Double) = Session(start, end, 1, 1, false)

    @Test
    fun `F needs two sessions`() {
        val m = windowMetrics(listOf(session(100.0, 200.0)), 0.0, 3600.0)
        assertEquals(1, m.n)
        assertEquals(0.0, m.f, 0.0)
        val two = windowMetrics(listOf(session(100.0, 200.0), session(1000.0, 1900.0)), 0.0, 3600.0)
        assertEquals(2 * 1800.0 / 3600.0, two.f, 1e-12)
    }

    @Test
    fun `S is log of the longest clipped duration, at least 1 s`() {
        assertEquals(ln(1.0), windowMetrics(listOf(session(10.0, 10.5)), 0.0, 3600.0).s, 0.0)
        // Clipped at the window end: 3600 − 3000.
        assertEquals(ln(600.0), windowMetrics(listOf(session(3000.0, 9000.0)), 0.0, 3600.0).s, 1e-12)
        assertEquals(0.0, windowMetrics(emptyList(), 0.0, 3600.0).s, 0.0)
    }

    @Test
    fun `no points after 3 h in a session`() {
        val points = decisionPoints(listOf(session(0.0, 5 * 3600.0)))
        assertEquals(18, points.size)
        assertEquals(17 * 600.0, points.last().t, 0.0)
        assertEquals(listOf(true) + List(17) { false }, points.map { it.isUnlock })
    }

    @Test
    fun `half-open window excludes a session ending exactly at a`() {
        assertEquals(0, windowMetrics(listOf(session(0.0, 100.0)), 100.0, 3600.0).n)
        assertEquals(0, windowMetrics(listOf(session(3700.0, 3800.0)), 100.0, 3600.0).n)
    }
}
