package com.example.smartuse_frontend.inference

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileReader
import kotlin.math.abs

class FeaturesTest {

    private val models = Pattern.entries.associateWith { SmartUseModel.load(FileReader(File("src/main/assets/${it.name}.model"))) }

    private fun close(expected: Double, actual: Double) = abs(expected - actual) <= 1e-5 * maxOf(1.0, abs(expected))

    @Test
    fun `feature names match the model files`() {
        assertEquals(models.getValue(Pattern.F).features().toList(), F_FEATURES)
        assertEquals(models.getValue(Pattern.S).features().toList(), S_FEATURES)
    }

    private fun checkVectors(pattern: Pattern) {
        val model = models.getValue(pattern)
        val names = model.features().toList()
        for (person in Fixtures.people) {
            val sessions = buildSessions(Fixtures.epochs(person))
            val profile = Json.decodeFromString(Profile.serializer(), Fixtures.profileJson(person, pattern).toString())
            val rows = Fixtures.csv(person, "vectors_${pattern.name}.csv")
            assertTrue(rows.size > 800)
            var offScore = 0
            for (row in rows) {
                val t = Fixtures.utcSeconds(row.getValue("t_utc"))
                val x = extract(pattern, sessions, t, profile, Fixtures.OFFSET_S)
                names.forEachIndexed { i, name ->
                    val want = row.getValue(name).toDouble()
                    assertTrue("$person ${pattern.name} t=${row["t_utc"]} $name: expected $want, got ${x[i]}", close(want, x[i].toDouble()))
                }
                val score = model.score(x)
                val expected = row.getValue("expected_score").toDouble()
                val diff = abs(score - expected)
                // A feature sitting on a tree split can fall on the other side by float noise
                // (BLAS summation order, seconds as Double): rare, small, never a different decision.
                if (diff > 1e-6) {
                    offScore++
                    println("$person ${pattern.name} t=${row["t_utc"]}: score off by $diff")
                }
                assertTrue("$person ${pattern.name} t=${row["t_utc"]} score off by $diff", diff <= 1e-2)
                // The fixture's expected_fires is at the training cutoff (F 0.446); the shipped F
                // cutoff is 0.302 (2026-10-22), so decide against the model's own cutoff, except
                // where this row's float noise could put the score on either side of it.
                if (abs(expected - model.cutoff()) > diff) {
                    assertEquals("$person ${pattern.name} t=${row["t_utc"]} fires", expected > model.cutoff(), model.fires(x))
                }
            }
            println("$person ${pattern.name}: ${rows.size} vectors, $offScore score(s) off by more than 1e-6")
            assertTrue("$person ${pattern.name}: $offScore of ${rows.size} scores off by more than 1e-6", offScore <= rows.size / 200)
        }
    }

    @Test
    fun `fixture vectors F`() = checkVectors(Pattern.F)

    @Test
    fun `fixture vectors S`() = checkVectors(Pattern.S)

    @Test
    fun burstiness() {
        assertEquals(0.0, burstiness(listOf(1.0, 1.0, 4.0)), 1e-12)
        assertEquals(-0.1547005, burstiness(listOf(1.0, 3.0)), 1e-6)
        assertEquals(-1.0, burstiness(listOf(1.0, 1.0, 1.0)), 1e-12)
        assertEquals(0.0, burstiness(listOf(0.0, 0.0, 0.0)), 0.0)
    }

    @Test
    fun `unlock point counts the new session in the 15-min window but not in the day block`() {
        // Local midnight at 0 (offset 0); two sessions, t at the second one's start.
        val sessions = listOf(Session(36_000.0, 36_100.0, 2, 1, false), Session(36_200.0, 36_300.0, 1, 1, false))
        val profile = Json.decodeFromString(Profile.serializer(), Fixtures.profileJson("u40466b", Pattern.S).toString())
        val x = extract(Pattern.S, sessions, 36_200.0, profile, 0.0)
        assertEquals(2f, x[S_FEATURES.indexOf("n_15")])
        assertEquals(0f, x[S_FEATURES.indexOf("cur_logdur")])
        assertEquals(1f, x[S_FEATURES.indexOf("freq_day")])
    }
}
