package com.example.smartuse_frontend.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class CanonicalSyncCursorTest {

    @Test
    fun `describe shows the local date-time and a short epoch id`() {
        val cursor = CanonicalSyncCursor(
            startTs = 1_700_000_000_123, // 2023-11-14T22:13:20.123Z
            epochId = "6f1c2b1e-0a0b-5c0d-8e0f-123456789abc",
        )

        assertEquals("14/11/2023 22:13:20 (id 6f1c2b1e)", cursor.describe(ZoneOffset.UTC))
    }

    @Test
    fun `describe of no cursor says nothing has been synced`() {
        assertEquals("nada sincronizado aún", describeCursor(null, ZoneOffset.UTC))
    }
}
