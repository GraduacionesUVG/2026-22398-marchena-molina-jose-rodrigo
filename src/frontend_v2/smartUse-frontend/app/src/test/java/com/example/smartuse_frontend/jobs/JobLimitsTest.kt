package com.example.smartuse_frontend.jobs

import org.junit.Assert.assertEquals
import org.junit.Test

class JobLimitsTest {

    @Test
    fun `missing or invalid values fall back to the defaults`() {
        assertEquals(JobLimits(cycleIntervalMinutes = 15, stuckAfterFailedRuns = 3), JobLimits.fromConfig(0, 0))
        assertEquals(JobLimits(cycleIntervalMinutes = 15, stuckAfterFailedRuns = 3), JobLimits.fromConfig(-5, -1))
    }

    @Test
    fun `configured values are used`() {
        assertEquals(JobLimits(cycleIntervalMinutes = 20, stuckAfterFailedRuns = 5), JobLimits.fromConfig(20, 5))
    }

    @Test
    fun `WorkManager's 15-minute floor is reflected in the effective interval`() {
        assertEquals(15L, JobLimits.fromConfig(5, 3).effectiveCycleIntervalMinutes)
        assertEquals(20L, JobLimits.fromConfig(20, 3).effectiveCycleIntervalMinutes)
    }
}
