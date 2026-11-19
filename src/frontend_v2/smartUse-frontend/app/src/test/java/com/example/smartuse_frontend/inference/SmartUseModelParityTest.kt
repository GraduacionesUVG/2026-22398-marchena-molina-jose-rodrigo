package com.example.smartuse_frontend.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileReader
import kotlin.math.abs

/** The bundled models against the researcher's parity vectors (models/parity), to 1e-6 as ParityCheck and extractor §10. */
class SmartUseModelParityTest {

    private fun model(pattern: Pattern) = SmartUseModel.load(FileReader(File("src/main/assets/${pattern.name}.model")))

    private fun rows(pattern: Pattern): List<List<String>> =
        javaClass.getResourceAsStream("/parity/${pattern.name}_vectors.csv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .map { it.split(",") }

    private fun checkParity(pattern: Pattern) {
        val model = model(pattern)
        val rows = rows(pattern)
        val n = model.features().size
        var maxDiff = 0.0
        for (row in rows.drop(1)) {
            val x = FloatArray(n) { row[it].toFloat() }
            val expected = row[n].toDouble()
            // The fixture's own decision column is at the training cutoff (F 0.446); the
            // shipped F cutoff is 0.302 (2026-10-22), so decide against the model's cutoff.
            val expectedFires = expected > model.cutoff()
            maxDiff = maxOf(maxDiff, abs(model.score(x) - expected))
            if (abs(expected - model.cutoff()) >= 1e-6) {
                assertEquals("decision at expected=$expected", expectedFires, model.fires(x))
            }
        }
        assertTrue("max diff $maxDiff", maxDiff <= 1e-6)
        assertEquals(3003, rows.size - 1)
    }

    @Test
    fun `F parity`() = checkParity(Pattern.F)

    @Test
    fun `S parity`() = checkParity(Pattern.S)

    @Test
    fun `header matches the model's feature order`() {
        for (pattern in Pattern.entries) {
            val model = model(pattern)
            assertEquals(model.features().toList(), rows(pattern).first().take(model.features().size))
        }
    }

    @Test
    fun cutoffs() {
        assertEquals(0.302, model(Pattern.F).cutoff(), 0.0)
        assertEquals(0.2415740185728322, model(Pattern.S).cutoff(), 0.0)
        assertEquals(78, model(Pattern.F).features().size)
        assertEquals(36, model(Pattern.S).features().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a vector of the wrong length`() {
        model(Pattern.S).score(FloatArray(35))
    }
}
