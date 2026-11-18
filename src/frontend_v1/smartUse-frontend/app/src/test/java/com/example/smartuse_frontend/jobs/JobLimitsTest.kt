package com.example.smartuse_frontend.jobs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class JobLimitsTest {

    @Test
    fun `valid config values are used as given`() {
        val limits = JobLimits.fromConfig(
            captureIntervalMinutes = 90,
            syncIntervalMinutes = 120,
            syncBatchSize = 25,
            stuckAfterFailedRuns = 4,
        )

        assertEquals(90L, limits.captureIntervalMinutes)
        assertEquals(120L, limits.syncIntervalMinutes)
        assertEquals(25, limits.syncBatchSize)
        assertEquals(4, limits.stuckAfterFailedRuns)
    }

    @Test
    fun `zero or negative config falls back to the defaults`() {
        val limits = JobLimits.fromConfig(0, -5, 0, -1)

        assertEquals(360L, limits.captureIntervalMinutes)
        assertEquals(360L, limits.syncIntervalMinutes)
        assertEquals(500, limits.syncBatchSize)
        assertEquals(3, limits.stuckAfterFailedRuns)
    }

    @Test
    fun `intervals under WorkManager's 15 minute floor report their effective value`() {
        val limits = JobLimits.fromConfig(5, 10, 0, 0)

        assertEquals(5L, limits.captureIntervalMinutes)
        assertEquals(15L, limits.effectiveCaptureIntervalMinutes)
        assertEquals(15L, limits.effectiveSyncIntervalMinutes)
    }

    @Test
    fun `intervals above the floor are unchanged`() {
        val limits = JobLimits.fromConfig(360, 720, 0, 0)

        assertEquals(360L, limits.effectiveCaptureIntervalMinutes)
        assertEquals(720L, limits.effectiveSyncIntervalMinutes)
    }

    @Test
    fun `describe reports the effective values`() {
        assertEquals(
            "capture every 15min, sync every 360min, batch 25",
            JobLimits.fromConfig(5, 360, 25, 0).describe(),
        )
    }

    @Test
    fun `fixed bounds match the spec`() {
        assertEquals(500, JobLimits.SYNC_BATCH_CEILING)
        assertEquals(50, JobLimits.MAX_BATCHES_PER_RUN)
        assertEquals(5, JobLimits.MAX_ATTEMPTS_PER_RUN)
        assertEquals(Duration.ofMinutes(3), JobLimits.RUN_TIME_BUDGET)
        assertEquals(Duration.ofSeconds(30), JobLimits.BACKOFF_INITIAL)
    }

    // The backend refuses more than 500 records per request (413); a debug
    // setting above that must never reach it.
    @Test
    fun `a batch size above the backend's 500 cap is clamped to 500`() {
        assertEquals(500, JobLimits.fromConfig(0, 0, 2_000, 0).syncBatchSize)
        assertEquals(500, JobLimits.fromConfig(0, 0, 622, 0).syncBatchSize)
        assertEquals(500, JobLimits.fromConfig(0, 0, 5_000, 0).syncBatchSize)
    }
}
