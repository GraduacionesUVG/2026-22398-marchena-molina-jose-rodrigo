package com.example.smartuse_frontend.inference

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.data.db.CanonicalDbHelper
import com.example.smartuse_frontend.data.db.RawCaptureDbHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteUsageSourceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val canonicalName = "usage-source-canonical-test.db"
    private val rawName = "usage-source-raw-test.db"
    private val canonical = CanonicalDbHelper(context, canonicalName)
    private val raw = RawCaptureDbHelper(context, rawName)
    private val source = SqliteUsageSource(canonical, raw)

    @After fun tearDown() {
        context.deleteDatabase(canonicalName)
        context.deleteDatabase(rawName)
    }

    private fun raw(id: Long, ts: Long, type: String, hash: String? = null) {
        raw.writableDatabase.insertOrThrow("raw_capture_log", null, ContentValues().apply {
            put("id", id); put("event_ts", ts); put("event_type", type); put("app_hash", hash)
        })
    }

    private fun epoch(start: Long, isUse: Boolean, apps: Int) {
        canonical.writableDatabase.insertOrThrow("epochs", null, ContentValues().apply {
            put("epoch_id", "e$start"); put("subject_id", "s"); put("start_ts", start)
            put("is_use", if (isUse) 1 else 0); put("distinct_app_count", apps); put("source", "device_capture")
        })
    }

    private fun cursor(id: Long) {
        canonical.writableDatabase.insertOrThrow("epoch_cursor", null, ContentValues().apply {
            put("id", 1); put("last_raw_log_id", id)
        })
    }

    @Test
    fun emptyDatabasesHaveNoFrontierAndNoEpochs() {
        assertNull(source.frontier())
        assertNull(source.firstEpochStart())
        assertEquals(emptyList<SeriesEpoch>(), source.closedEpochs(0, 1_000))
        assertFalse(source.hasScreenEventAtOrAfter(0))
    }

    @Test
    fun readsTheFrontierAndTheEpochsBeforeIt() {
        raw(1, 100, "SCREEN_INTERACTIVE")
        raw(2, 150, "ACTIVITY_RESUMED", "a")
        raw(3, 300, "SCREEN_NON_INTERACTIVE")
        raw(4, 700, "SCREEN_INTERACTIVE")
        raw(5, 750, "ACTIVITY_RESUMED", "b")
        epoch(100, true, 1)
        epoch(300, false, 0)
        cursor(3) // parked before the dangling screen-on (id 4)

        assertEquals(700L, source.frontier())
        assertEquals(100L, source.firstEpochStart())
        assertEquals(
            listOf(SeriesEpoch(100, 300, true, 1), SeriesEpoch(300, 700, false, 0)),
            source.closedEpochs(0, 700),
        )
        assertEquals(listOf(SeriesEpoch(300, 700, false, 0)), source.closedEpochs(400, 700))
        assertEquals(300L, source.lastScreenEventTs(699))
        assertTrue(source.hasScreenEventAtOrAfter(700))
        assertFalse(source.hasScreenEventAtOrAfter(701))
        assertEquals(listOf(4L, 5L), source.rawEvents(700, 800).map { it.id })
    }
}
