package com.example.smartuse_frontend.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

private const val DB_NAME = "raw_capture.db"
private const val DB_VERSION = 2

/**
 * Raw capture log table — the
 * not-yet-processed projection of UsageStatsManager events, one row per
 * captured event. Ephemeral/rotating by design; kept in its own DB file
 * since canonical-schema.md explicitly excludes it from the canonical
 * tables ("just an audit trail").
 *
 * `capture_cursor` is a single-row table (pinned via CHECK (id = 1)) holding
 * the last processed event_ts, so cursor state doesn't depend on which rows
 * still happen to be present in raw_capture_log once cleanup exists.
 */
class RawCaptureDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) = createSchema(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS raw_capture_log")
        db.execSQL("DROP TABLE IF EXISTS capture_cursor")
        createSchema(db)
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS raw_capture_log (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                event_ts   INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                app_hash   TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_raw_capture_log_event_ts ON raw_capture_log (event_ts)",
        )
        // Dedup backstop for a cursor re-read that overlaps already-stored
        // rows. COALESCE(app_hash, '') avoids the SQL NULL-never-equals-NULL
        // gotcha that would otherwise let screen on/off rows (always a null
        // app_hash) duplicate freely — event_type alone already keeps
        // screen-on and screen-off from ever colliding with each other.
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_raw_capture_log_dedup
                ON raw_capture_log (event_ts, event_type, COALESCE(app_hash, ''))
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS capture_cursor (
                id            INTEGER PRIMARY KEY CHECK (id = 1),
                last_event_ts INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}
