package com.example.smartuse_frontend.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsTest {

    private fun use(start: Double, end: Double, apps: Int, open: Boolean = false) = ModelEpoch(start, end, true, apps, open)
    private fun off(start: Double, end: Double) = ModelEpoch(start, end, false, 0)

    @Test
    fun `fixture sessions`() {
        for (person in Fixtures.people) {
            val expected = Fixtures.csv(person, "sessions.csv")
            val sessions = buildSessions(Fixtures.epochs(person))
            assertEquals("$person count", expected.size, sessions.size)
            expected.zip(sessions).forEachIndexed { i, (row, s) ->
                val at = "$person row $i"
                assertEquals("$at start", Fixtures.utcSeconds(row.getValue("start_utc")), s.start, 1e-3)
                assertEquals("$at end", Fixtures.utcSeconds(row.getValue("end_utc")), s.end, 1e-3)
                assertEquals("$at duration", row.getValue("duration_s").toDouble(), s.end - s.start, 1e-3)
                assertEquals("$at n_epochs", row.getValue("n_epochs").toInt(), s.nEpochs)
                assertEquals("$at apps", row.getValue("app_count_max").toInt(), s.apps)
                assertEquals("$at no_close", Fixtures.bool(row.getValue("no_close")), s.noClose)
            }
        }
    }

    @Test
    fun `zero-app use epoch counts as non-use before joining`() {
        val sessions = buildSessions(
            listOf(use(0.0, 10.0, 2), off(10.0, 20.0), use(20.0, 25.0, 0), off(25.0, 40.0), use(40.0, 50.0, 1)),
        )
        assertEquals(listOf(0.0 to 10.0, 40.0 to 50.0), sessions.map { it.start to it.end })
    }

    @Test
    fun `gap under 30 s joins, apps summed`() {
        val sessions = buildSessions(listOf(use(0.0, 10.0, 2), off(10.0, 39.9), use(39.9, 50.0, 3)))
        assertEquals(1, sessions.size)
        assertEquals(Session(0.0, 50.0, 5, 2, false), sessions.single())
    }

    @Test
    fun `gap of exactly 30 s does not join`() {
        val sessions = buildSessions(listOf(use(0.0, 10.0, 2), off(10.0, 40.0), use(40.0, 50.0, 3)))
        assertEquals(2, sessions.size)
    }

    @Test
    fun `same-type epochs merge with max apps and first start`() {
        assertEquals(
            listOf(use(0.0, 20.0, 3), off(20.0, 100.0)),
            mergeEpochs(listOf(use(0.0, 10.0, 3), use(10.0, 20.0, 1), off(20.0, 50.0), off(50.0, 100.0))),
        )
    }

    @Test
    fun `open use epoch with 0 apps is kept`() {
        val sessions = buildSessions(listOf(use(0.0, 10.0, 1), off(10.0, 100.0), use(100.0, 100.0, 0, open = true)))
        assertEquals(2, sessions.size)
        assertEquals(100.0, sessions.last().start, 0.0)
        assertEquals(0, sessions.last().apps)
    }

    @Test
    fun `over 3 h is noClose`() {
        val sessions = buildSessions(listOf(use(0.0, 3 * 3600.0 + 1, 1), use(20_000.0, 20_010.0, 1)))
        assertTrue(sessions.first().noClose)
        assertFalse(buildSessions(listOf(use(0.0, 3 * 3600.0, 1))).single().noClose)
    }
}
