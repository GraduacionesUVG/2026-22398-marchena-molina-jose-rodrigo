package com.example.smartuse_frontend.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobSpecsTest {

    private val limits = JobLimits.fromConfig(360, 720, 500, 3)

    @Test
    fun `the capture and sync jobs use the configured intervals`() {
        val specs = JobSpecs.periodic(limits).associateBy { it.uniqueName }

        assertEquals(360L, specs.getValue(JobSpecs.CAPTURE_PERIODIC).intervalMinutes)
        assertEquals(720L, specs.getValue(JobSpecs.SYNC_PERIODIC).intervalMinutes)
    }

    @Test
    fun `intervals below WorkManager's floor are scheduled at the floor`() {
        val specs = JobSpecs.periodic(JobLimits.fromConfig(5, 1, 500, 3))
            .associateBy { it.uniqueName }

        assertEquals(15L, specs.getValue(JobSpecs.CAPTURE_PERIODIC).intervalMinutes)
        assertEquals(15L, specs.getValue(JobSpecs.SYNC_PERIODIC).intervalMinutes)
    }

    @Test
    fun `only the sync jobs need a network`() {
        val specs = JobSpecs.periodic(limits).associateBy { it.uniqueName }

        assertFalse(specs.getValue(JobSpecs.CAPTURE_PERIODIC).requiresNetwork)
        assertTrue(specs.getValue(JobSpecs.SYNC_PERIODIC).requiresNetwork)
        assertTrue(JobSpecs.syncNow.requiresNetwork)
    }

    @Test
    fun `periodic jobs update in place, the one-shot keeps a pending run`() {
        JobSpecs.periodic(limits).forEach { assertEquals(SchedulePolicy.UPDATE, it.policy) }
        assertEquals(SchedulePolicy.KEEP, JobSpecs.syncNow.policy)
    }

    @Test
    fun `each spec runs the right kind of job`() {
        val specs = JobSpecs.periodic(limits).associateBy { it.uniqueName }

        assertEquals(JobKind.CAPTURE, specs.getValue(JobSpecs.CAPTURE_PERIODIC).kind)
        assertEquals(JobKind.SYNC, specs.getValue(JobSpecs.SYNC_PERIODIC).kind)
        assertEquals(JobKind.SYNC, JobSpecs.syncNow.kind)
    }

    @Test
    fun `allNames lists every unique work so cancelAll cannot miss one`() {
        assertEquals(
            setOf("capture-periodic", "sync-periodic", "sync-now"),
            JobSpecs.allNames.toSet(),
        )
    }

    @Test
    fun `the removed watcher's name is kept only for cancelling it`() {
        assertEquals("permission-watch-periodic", JobSpecs.LEGACY_PERMISSION_WATCH_PERIODIC)
        assertFalse(JobSpecs.LEGACY_PERMISSION_WATCH_PERIODIC in JobSpecs.allNames)
    }
}
