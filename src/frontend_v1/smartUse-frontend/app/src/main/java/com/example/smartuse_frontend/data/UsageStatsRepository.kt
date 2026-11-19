package com.example.smartuse_frontend.data

import java.time.LocalDate
import java.time.ZoneId

data class EpochRecord(
    val startTs: Long,
    // Always concrete: derived from the next epoch's start_ts, or "now" for
    // the single most recent epoch overall (no successor has closed yet).
    val endTs: Long,
    val isUse: Boolean,
    val distinctAppCount: Int,
)

interface UsageStatsRepository {
    // Epochs whose [startTs, endTs) overlaps the given calendar day.
    suspend fun getEpochsOverlappingDay(day: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): List<EpochRecord>
}
