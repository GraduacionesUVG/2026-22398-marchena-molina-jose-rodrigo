package com.example.smartuse_frontend.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenObservationLogTest {
    private val log = ScreenObservationLog(cap = 3)

    @Test
    fun `starting records the current state at that moment`() {
        log.startObserving(now = 100, screenOn = true)
        assertEquals(ObservationSnapshot(100, listOf(ScreenObservation(100, true))), log.snapshot())
    }

    @Test
    fun `transitions are recorded only while observing`() {
        log.record(50, false)
        log.startObserving(100, true)
        log.record(200, false)
        log.stopObserving()
        log.record(300, true)

        assertEquals(ObservationSnapshot.NONE, log.snapshot())
    }

    @Test
    fun `pruning drops observations at or before a time`() {
        log.startObserving(100, true)
        log.record(200, false)
        log.record(300, true)
        log.pruneAtOrBefore(200)

        assertEquals(listOf(ScreenObservation(300, true)), log.snapshot().observations)
        assertEquals(100L, log.snapshot().observingSince)
    }

    @Test
    fun `the cap keeps the newest observations`() {
        log.startObserving(100, true)
        log.record(200, false)
        log.record(300, true)
        log.record(400, false)

        assertEquals(listOf(200L, 300L, 400L), log.snapshot().observations.map { it.ts })
    }
}
