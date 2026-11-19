package com.example.smartuse_frontend.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobSpecsTest {

    private val limits = JobLimits(cycleIntervalMinutes = 360, stuckAfterFailedRuns = 3)

    @Test
    fun `only the cycle is scheduled, without a network constraint, updating its interval`() {
        val spec = JobSpecs.periodic(limits).single()
        assertEquals(JobSpecs.CYCLE_PERIODIC, spec.uniqueName)
        assertEquals(JobKind.CYCLE, spec.kind)
        assertEquals(360L, spec.intervalMinutes)
        assertEquals(false, spec.requiresNetwork)
        assertEquals(SchedulePolicy.UPDATE, spec.policy)
    }

    @Test
    fun `cancelling everything covers every scheduled name and every retired one`() {
        assertEquals(JobSpecs.periodic(limits).map { it.uniqueName } + JobSpecs.retiredNames, JobSpecs.allNames)
        assertTrue("capture-periodic" in JobSpecs.retiredNames)
    }

    @Test
    fun `the final send is never cancelled with the cycle`() {
        assertEquals("final-send", JobSpecs.FINAL_SEND)
        assertFalse(JobSpecs.FINAL_SEND in JobSpecs.allNames)
    }
}
