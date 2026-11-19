package com.example.smartuse_frontend.metrics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Its own file, apart from the usage databases: the end-of-study erase never touches it. */
const val METRICS_DATABASE = "metrics.db"
// v1: cycle_metrics (one per WorkManager cycle). v2: decision_metrics (one per decision point).
private const val DB_VERSION = 2

/** `decision_metrics`: one row per decision point that ran the models. The id only orders the records. */
class MetricsDbHelper(context: Context, name: String = METRICS_DATABASE) :
    SQLiteOpenHelper(context.applicationContext, name, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) = createDecisionMetrics(db)

    // Only development builds ever had v1 records: replaced, not migrated.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS cycle_metrics")
            createDecisionMetrics(db)
        }
    }

    private fun createDecisionMetrics(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS decision_metrics (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                decision_point    TEXT NOT NULL CHECK (decision_point IN ('screen_on', 'active_use')),
                duration_ms       INTEGER NOT NULL CHECK (duration_ms >= 0),
                battery_delta_pct INTEGER,
                current_mean      INTEGER,
                peak_rss_kb       INTEGER
            )
            """.trimIndent(),
        )
    }
}
