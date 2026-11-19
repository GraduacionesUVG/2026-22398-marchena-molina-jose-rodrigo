package com.example.smartuse_frontend.metrics

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlMetricsStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "metrics-test.db"
    private val helper = MetricsDbHelper(context, name)
    private val store = SqlMetricsStore(helper)

    @After fun tearDown() { context.deleteDatabase(name) }

    @Test
    fun insertsAndCounts() {
        assertEquals(0, store.count())
        store.insert(DecisionMetric(DecisionKind.SCREEN_ON, 412, 0, -310_000, 98_304))
        store.insert(DecisionMetric(DecisionKind.ACTIVE_USE, 35, null, null, null))
        assertEquals(2, store.count())
    }

    @Test
    fun readsEveryRecordBackInOrder() {
        val records = listOf(
            DecisionMetric(DecisionKind.SCREEN_ON, 412, 0, -310_000, 98_304),
            DecisionMetric(DecisionKind.ACTIVE_USE, 35, null, null, null),
        )
        records.forEach(store::insert)

        assertEquals(records, store.readAll())
    }

    @Test
    fun storesTheSpecsColumnsWithNulls() {
        store.insert(DecisionMetric(DecisionKind.ACTIVE_USE, 7, null, null, null))
        helper.readableDatabase.use { db ->
            db.rawQuery(
                "SELECT id, decision_point, duration_ms, battery_delta_pct, current_mean, peak_rss_kb FROM decision_metrics",
                null,
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("active_use", c.getString(1))
                assertEquals(7L, c.getLong(2))
                assertTrue(c.isNull(3) && c.isNull(4) && c.isNull(5))
            }
        }
    }

    @Test
    fun rejectsAnUnknownDecisionPointAndANegativeDuration() {
        helper.writableDatabase.use { db ->
            assertThrows(SQLiteConstraintException::class.java) {
                db.insertOrThrow("decision_metrics", null, ContentValues().apply {
                    put("decision_point", "inference"); put("duration_ms", 1)
                })
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.insertOrThrow("decision_metrics", null, ContentValues().apply {
                    put("decision_point", "screen_on"); put("duration_ms", -1)
                })
            }
        }
        assertEquals(0, store.count())
    }

    @Test
    fun upgradeFromV1ReplacesCycleMetrics() {
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE cycle_metrics (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, duration_ms INTEGER NOT NULL)")
            db.execSQL("INSERT INTO cycle_metrics (kind, duration_ms) VALUES ('inference', 5)")
            db.version = 1
        }
        val upgraded = SqlMetricsStore(MetricsDbHelper(context, name))
        assertEquals(0, upgraded.count())
        upgraded.insert(DecisionMetric(DecisionKind.SCREEN_ON, 9, null, null, null))
        assertEquals(1, upgraded.count())
    }
}
