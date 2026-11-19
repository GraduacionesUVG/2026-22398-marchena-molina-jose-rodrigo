package com.example.smartuse_frontend.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// Uses its own database file, so the app's real canonical.db on the device is never touched.
@RunWith(AndroidJUnit4::class)
class CanonicalDbMigrationInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "canonical-migration-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun upgradingFromV4KeepsEveryEpochAndMarksNoneSkipped() {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL(
                """
                CREATE TABLE epochs (
                    epoch_id TEXT PRIMARY KEY, subject_id TEXT NOT NULL, start_ts INTEGER NOT NULL,
                    is_use INTEGER NOT NULL, distinct_app_count INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO epochs VALUES ('e-1', 's', 1000, 1, 2, 'device_capture')")
            db.execSQL("INSERT INTO epochs VALUES ('e-2', 's', 2000, 0, 0, 'device_capture')")
            db.version = 4
        }

        CanonicalDbHelper(context, name).readableDatabase.use { db ->
            db.rawQuery("SELECT epoch_id, sync_skipped FROM epochs ORDER BY start_ts", null).use { c ->
                val rows = buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
                assertEquals(listOf("e-1" to 0, "e-2" to 0), rows)
            }
            assertEquals(5, db.version)
        }
    }
}
