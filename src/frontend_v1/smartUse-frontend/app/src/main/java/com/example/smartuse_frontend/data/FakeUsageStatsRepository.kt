package com.example.smartuse_frontend.data

import java.time.LocalDate
import java.time.ZoneId

class FakeUsageStatsRepository(
    // A var so a test can add epochs after the ViewModel has already loaded.
    var epochsByDay: Map<LocalDate, List<EpochRecord>> = emptyMap(),
) : UsageStatsRepository {
    override suspend fun getEpochsOverlappingDay(day: LocalDate, zoneId: ZoneId): List<EpochRecord> =
        epochsByDay[day].orEmpty()
}
