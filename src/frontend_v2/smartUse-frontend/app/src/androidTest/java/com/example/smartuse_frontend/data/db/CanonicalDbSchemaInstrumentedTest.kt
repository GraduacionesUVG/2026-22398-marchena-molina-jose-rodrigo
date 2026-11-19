package com.example.smartuse_frontend.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanonicalDbSchemaInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "canonical-schema-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun aFreshDatabaseIsVersionThreeWithProfilesAndWithoutLongBins() {
        val db = CanonicalDbHelper(context, name).writableDatabase
        assertEquals(3, db.version)
        val columns = columnsOf(db, "epochs")
        assertEquals(listOf("epoch_id", "subject_id", "start_ts", "is_use", "distinct_app_count", "source"), columns)
        assertFalse(columns.contains("sync_skipped"))
        assertEquals(listOf("pattern", "profile_json"), columnsOf(db, "model_profiles"))
        assertEquals(emptyList<String>(), columnsOf(db, "long_bins"))
        db.close()
    }

    @Test
    fun upgradeFromV2DropsLongBinsAndKeepsEpochs() {
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE epochs (epoch_id TEXT PRIMARY KEY, subject_id TEXT NOT NULL, start_ts INTEGER NOT NULL, is_use INTEGER NOT NULL, distinct_app_count INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL)")
            db.execSQL("INSERT INTO epochs VALUES ('e1', 's', 1000, 1, 2, 'device_capture')")
            db.execSQL("CREATE TABLE long_bins (bin_start INTEGER PRIMARY KEY)")
            db.version = 2
        }
        val db = CanonicalDbHelper(context, name).writableDatabase
        assertEquals(3, db.version)
        assertEquals(emptyList<String>(), columnsOf(db, "long_bins"))
        assertEquals(listOf("pattern", "profile_json"), columnsOf(db, "model_profiles"))
        db.rawQuery("SELECT COUNT(*) FROM epochs", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.close()
    }

    private fun columnsOf(db: SQLiteDatabase, table: String) =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
}
