package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.data.db.RAW_LOG_EXPORT_FILE
import com.example.smartuse_frontend.metrics.METRICS_DATABASE
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalDataWiperTest {

    @Test
    fun `wipe deletes the usage databases`() = runTest {
        var deleted = 0
        LocalDataWiper(deleteDatabases = { deleted++ }, logger = {}).wipe()
        assertEquals(1, deleted)
    }

    @Test
    fun `the erase also removes the raw-log export, a copy of the usage data`() {
        assertEquals("raw_capture_log_export.csv", RAW_LOG_EXPORT_FILE)
        assertEquals(listOf(RAW_LOG_EXPORT_FILE), USAGE_CACHE_FILES)
    }

    @Test
    fun `a failing delete is logged, not thrown`() = runTest {
        val logs = mutableListOf<String>()
        LocalDataWiper(deleteDatabases = { throw IllegalStateException("boom") }, logger = { logs += it }).wipe()
        assertEquals(1, logs.size)
    }

    // Credentials, Keystore keys, job state and performance metrics are never part of the wipe.
    @Test
    fun `only the usage databases are wiped`() {
        assertEquals(listOf("canonical.db", "raw_capture.db"), USAGE_DATABASES)
    }

    @Test
    fun `the end-of-study erase never touches the performance metrics`() {
        assertFalse(METRICS_DATABASE in USAGE_DATABASES)
    }
}
