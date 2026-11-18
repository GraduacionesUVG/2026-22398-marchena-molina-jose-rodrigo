package com.example.smartuse_frontend.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

private const val DB_NAME = "canonical.db"
// v1: epochs, epoch_apps, epoch_cursor (fresh applicationId, nothing to
// migrate from the data-collection app). v2: long_bins (the LSTM's settled
// long-bin cache). v3: long_bins dropped, model_profiles (the frozen F/S
// profiles).
private const val DB_VERSION = 3

/**
 * The canonical epoch/epoch_apps schema. Single physical table pair (6-minute
 * stems), plus `model_profiles`, the two frozen model profiles.
 *
 * `epoch_cursor` is a single-row table (pinned via CHECK (id = 1)) holding
 * the raw_capture_log.id just *before* the dangling screen event: the last one
 * read, which closed the final epoch written but also opens the next one, so
 * the next run (`id > cursor`) must read it again. A row-based cursor, since
 * raw_capture_log has its own unambiguous row identity (unlike
 * UsageStatsManager, which has none). See buildEpochs in EpochBuilder.kt.
 */
class CanonicalDbHelper(context: Context, name: String = DB_NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, DB_VERSION) {

    // PRAGMA/connection settings are per-connection, not per-file — must be
    // (re)applied here, not in onCreate, which only runs once ever.
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = createSchema(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS long_bins")
            createModelProfiles(db)
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS epochs (
                epoch_id           TEXT PRIMARY KEY,
                subject_id         TEXT NOT NULL,
                start_ts           INTEGER NOT NULL,
                is_use             INTEGER NOT NULL CHECK (is_use IN (0, 1)),
                distinct_app_count INTEGER NOT NULL DEFAULT 0 CHECK (distinct_app_count >= 0),
                source             TEXT NOT NULL CHECK (source IN ('lsapp', 'device_capture')),

                CHECK (is_use = 1 OR distinct_app_count = 0)
            )
            """.trimIndent(),
        )
        // Query path for "this epoch's end = next row's start_ts" (see
        // canonical-schema.md), and a correctness backstop: a repeated
        // start_ts for the same subject means an epoch got built twice.
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_epochs_subject_start
                ON epochs (subject_id, start_ts)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS epoch_apps (
                epoch_id TEXT NOT NULL REFERENCES epochs (epoch_id) ON DELETE CASCADE,
                app_hash TEXT NOT NULL,

                PRIMARY KEY (epoch_id, app_hash)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS epoch_cursor (
                id               INTEGER PRIMARY KEY CHECK (id = 1),
                last_raw_log_id  INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        createModelProfiles(db)
    }

    // The frozen F and S profiles: one row each, written
    // once, never rewritten. Usage data: erased with this file.
    private fun createModelProfiles(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS model_profiles (
                pattern      TEXT PRIMARY KEY CHECK (pattern IN ('F', 'S')),
                profile_json TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }
}
