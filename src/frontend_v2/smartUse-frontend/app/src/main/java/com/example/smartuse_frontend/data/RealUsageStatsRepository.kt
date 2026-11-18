package com.example.smartuse_frontend.data

import android.content.Context
import com.example.smartuse_frontend.data.db.CanonicalDbHelper
import java.time.LocalDate
import java.time.ZoneId

/** Real [UsageStatsRepository], backed by the on-device canonical DB. */
class RealUsageStatsRepository(private val context: Context) : UsageStatsRepository {

    override suspend fun getEpochsOverlappingDay(day: LocalDate, zoneId: ZoneId): List<EpochRecord> {
        val dayStart = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        return CanonicalDbHelper(context).readableDatabase.use { db ->
            db.rawQuery(
                """
                SELECT e1.start_ts, e1.is_use, e1.distinct_app_count,
                       (SELECT MIN(e2.start_ts) FROM epochs e2
                        WHERE e2.subject_id = e1.subject_id AND e2.start_ts > e1.start_ts) AS derived_end_ts
                FROM epochs e1
                WHERE e1.start_ts < ?
                ORDER BY e1.start_ts
                """.trimIndent(),
                arrayOf(dayEnd.toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val startTs = cursor.getLong(0)
                        val endTs = if (cursor.isNull(3)) now else cursor.getLong(3)
                        if (endTs > dayStart) {
                            add(
                                EpochRecord(
                                    startTs = startTs,
                                    endTs = endTs,
                                    isUse = cursor.getInt(1) == 1,
                                    distinctAppCount = cursor.getInt(2),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
