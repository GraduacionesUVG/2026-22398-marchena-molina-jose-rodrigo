package com.example.smartuse_frontend.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicEpochIdTest {

    private fun id(
        subjectId: String = "user-1",
        startTs: Long = 1_700_000_000_000,
        isUse: Boolean = true,
        closingEventTs: Long = 1_700_000_060_000,
        closingEventType: String = "SCREEN_NON_INTERACTIVE",
    ) = deterministicEpochId(subjectId, startTs, isUse, closingEventTs, closingEventType)

    // The backend types client_record_id as `format: uuid`, so the hash must
    // be shaped as a UUID (valid version + RFC 4122 variant nibbles).
    @Test
    fun `id is shaped as a valid UUID`() {
        val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue(id(), uuid.matches(id()))
    }

    @Test
    fun `same inputs always give the same id`() {
        assertEquals(id(), id())
    }

    @Test
    fun `every identifying field changes the id`() {
        val base = id()
        assertNotEquals(base, id(subjectId = "user-2"))
        assertNotEquals(base, id(startTs = 1_700_000_000_001))
        assertNotEquals(base, id(isUse = false))
        assertNotEquals(base, id(closingEventTs = 1_700_000_060_001))
        assertNotEquals(base, id(closingEventType = "SCREEN_INTERACTIVE"))
    }
}
