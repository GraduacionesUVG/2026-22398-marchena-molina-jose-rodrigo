package com.example.smartuse_frontend.data.db

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class CaptureStartTest {

    private fun ms(v: Long) = Instant.ofEpochMilli(v)

    @Test
    fun `the cursor wins`() = assertEquals(5L, captureStart(5L, ms(3), ms(4), 100))

    @Test
    fun `the consented history comes before signup`() = assertEquals(3L, captureStart(null, ms(3), ms(4), 100))

    @Test
    fun `signup without history`() = assertEquals(4L, captureStart(null, null, ms(4), 100))

    @Test
    fun `nothing at all reads the last 24 h`() = assertEquals(7L, captureStart(null, null, null, 86_400_000L + 7))
}
