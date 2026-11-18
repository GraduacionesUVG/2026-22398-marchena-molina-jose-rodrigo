package com.example.smartuse_frontend.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FakeUsageStatsRepositoryTest {

    @Test
    fun `returns the epochs mapped to the requested day`() = runTest {
        val day = LocalDate.of(2026, 1, 2)
        val epochs = listOf(EpochRecord(startTs = 0L, endTs = 1000L, isUse = true, distinctAppCount = 1))
        val repo = FakeUsageStatsRepository(mapOf(day to epochs))

        assertEquals(epochs, repo.getEpochsOverlappingDay(day))
    }

    @Test
    fun `returns an empty list for a day with no mapped epochs`() = runTest {
        val repo = FakeUsageStatsRepository(emptyMap())

        assertEquals(emptyList<EpochRecord>(), repo.getEpochsOverlappingDay(LocalDate.of(2026, 1, 2)))
    }
}
