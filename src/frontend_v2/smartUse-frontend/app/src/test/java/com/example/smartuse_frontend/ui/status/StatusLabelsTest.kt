package com.example.smartuse_frontend.ui.status

import com.example.smartuse_frontend.jobs.JobHealth
import com.example.smartuse_frontend.ui.common.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class StatusLabelsTest {

    private val at = Instant.parse("2026-09-20T14:05:00Z")
    private val since = Instant.parse("2026-09-18T09:00:00Z")
    private val utc = ZoneOffset.UTC

    @Test
    fun `capture shows the last successful run`() {
        val health = JobHealth(lastSuccessAt = at, stuckSince = null)

        assertEquals("Última ejecución: 20 sept, 14:05", captureDetail(health, utc))
        assertEquals(StatusTone.SUCCESS, captureTone(health))
    }

    @Test
    fun `capture that never ran says so`() {
        val health = JobHealth(null, null)

        assertEquals("Aún no se ha ejecutado", captureDetail(health, utc))
        assertEquals(StatusTone.NEUTRAL, captureTone(health))
    }

    @Test
    fun `stuck capture reports since when, and wins over an older success`() {
        val health = JobHealth(lastSuccessAt = at, stuckSince = since)

        assertEquals("Falla desde: 18 sept, 09:00", captureDetail(health, utc))
        assertEquals(StatusTone.ERROR, captureTone(health))
    }

    @Test
    fun `each job tone has a short pill label`() {
        assertEquals("Al día", jobStateLabel(StatusTone.SUCCESS))
        assertEquals("Con fallas", jobStateLabel(StatusTone.ERROR))
        assertEquals("Pendiente", jobStateLabel(StatusTone.NEUTRAL))
    }
}
