package com.example.smartuse_frontend.inference

import com.example.smartuse_frontend.data.db.CanonicalDbHelper
import com.example.smartuse_frontend.data.db.RawCaptureDbHelper
import com.example.smartuse_frontend.data.db.RawEvent
import com.example.smartuse_frontend.data.db.readEpochCursor

private const val SCREEN_TYPES = "('SCREEN_INTERACTIVE', 'SCREEN_NON_INTERACTIVE')"
private const val SERIES_TYPES = "('SCREEN_INTERACTIVE', 'SCREEN_NON_INTERACTIVE', 'ACTIVITY_RESUMED')"

/** canonical.db and raw_capture.db as the timeline reads them. Single subject per device. */
internal class SqliteUsageSource(
    private val canonical: CanonicalDbHelper,
    private val raw: RawCaptureDbHelper,
) : UsageSource {

    // The cursor parks just before the dangling screen event, so the first
    // screen row after it is where the open epoch starts.
    override fun frontier(): Long? {
        val cursor = readEpochCursor(canonical)
        return raw.readableDatabase.use { db ->
            db.rawQuery(
                "SELECT event_ts FROM raw_capture_log WHERE id > ? AND event_type IN $SCREEN_TYPES ORDER BY id LIMIT 1",
                arrayOf(cursor.toString()),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }
    }

    override fun lastScreenEventTs(atOrBefore: Long): Long? = raw.readableDatabase.use { db ->
        db.rawQuery(
            "SELECT MAX(event_ts) FROM raw_capture_log WHERE event_type IN $SCREEN_TYPES AND event_ts <= ?",
            arrayOf(atOrBefore.toString()),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }

    override fun hasScreenEventAtOrAfter(ts: Long): Boolean = raw.readableDatabase.use { db ->
        db.rawQuery(
            "SELECT 1 FROM raw_capture_log WHERE event_type IN $SCREEN_TYPES AND event_ts >= ? LIMIT 1",
            arrayOf(ts.toString()),
        ).use { c -> c.moveToFirst() }
    }

    override fun rawEvents(from: Long, to: Long): List<RawEvent> = raw.readableDatabase.use { db ->
        db.rawQuery(
            "SELECT id, event_ts, event_type, app_hash FROM raw_capture_log " +
                "WHERE event_ts >= ? AND event_ts < ? AND event_type IN $SERIES_TYPES ORDER BY event_ts, id",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(RawEvent(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3))) }
        }
    }

    override fun closedEpochs(from: Long, end: Long): List<SeriesEpoch> {
        val rows = canonical.readableDatabase.use { db ->
            db.rawQuery(
                "SELECT start_ts, is_use, distinct_app_count FROM epochs " +
                    "WHERE start_ts < ? AND start_ts >= COALESCE((SELECT MAX(start_ts) FROM epochs WHERE start_ts <= ?), 0) " +
                    "ORDER BY start_ts",
                arrayOf(end.toString(), from.toString()),
            ).use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getInt(1) == 1, c.getInt(2))) }
            }
        }
        return rows.mapIndexed { i, (start, isUse, apps) ->
            SeriesEpoch(start, rows.getOrNull(i + 1)?.first ?: end, isUse, apps)
        }
    }

    override fun firstEpochStart(): Long? = canonical.readableDatabase.use { db ->
        db.rawQuery("SELECT MIN(start_ts) FROM epochs", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }
}
