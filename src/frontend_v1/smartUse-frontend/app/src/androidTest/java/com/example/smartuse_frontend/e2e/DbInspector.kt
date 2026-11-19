package com.example.smartuse_frontend.e2e

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.smartuse_frontend.data.RealSyncCursorStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.time.Instant

data class Epoch(val id: String, val startTs: Long, val isUse: Boolean, val apps: Int, val skipped: Boolean)

/** Local database consistency, checked at the end of every test. */
object DbInspector {
    private fun open(context: Context, name: String): SQLiteDatabase? =
        context.getDatabasePath(name).takeIf { it.exists() }
            ?.let { SQLiteDatabase.openDatabase(it.path, null, SQLiteDatabase.OPEN_READONLY) }

    fun databasesExist(context: Context): Boolean =
        context.getDatabasePath("canonical.db").exists() || context.getDatabasePath("raw_capture.db").exists()

    fun epochs(context: Context): List<Epoch> = open(context, "canonical.db")?.use { db ->
        db.rawQuery("SELECT epoch_id, start_ts, is_use, distinct_app_count, sync_skipped FROM epochs ORDER BY start_ts", null).use { c ->
            buildList { while (c.moveToNext()) add(Epoch(c.getString(0), c.getLong(1), c.getInt(2) == 1, c.getInt(3), c.getInt(4) == 1)) }
        }
    } ?: emptyList()

    fun assertConsistent(context: Context, backend: FakeBackend) {
        for (name in listOf("raw_capture.db", "canonical.db")) {
            open(context, name)?.use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst(); assertEquals("$name integrity", "ok", c.getString(0)) }
                db.rawQuery("PRAGMA foreign_key_check", null).use { c -> assertEquals("$name foreign keys", 0, c.count) }
            }
        }
        open(context, "raw_capture.db")?.use { db ->
            db.rawQuery(
                "SELECT COUNT(*) FROM (SELECT 1 FROM raw_capture_log GROUP BY event_ts, event_type, IFNULL(app_hash, '') HAVING COUNT(*) > 1)",
                null,
            ).use { c -> c.moveToFirst(); assertEquals("duplicate raw events", 0, c.getInt(0)) }
            db.rawQuery("SELECT (SELECT last_event_ts FROM capture_cursor WHERE id = 1), (SELECT MAX(event_ts) FROM raw_capture_log)", null).use { c ->
                c.moveToFirst()
                if (!c.isNull(0) && !c.isNull(1)) assertTrue("capture cursor ahead of the raw log", c.getLong(0) <= c.getLong(1))
            }
        }
        val list = epochs(context)
        list.zipWithNext().forEach { (a, b) -> assertTrue("epochs not strictly increasing at ${a.id}", a.startTs < b.startTs) }
        list.filter { !it.isUse }.forEach { assertEquals("idle epoch ${it.id} with apps", 0, it.apps) }
        open(context, "canonical.db")?.use { canon ->
            canon.rawQuery("SELECT last_raw_log_id FROM epoch_cursor WHERE id = 1", null).use { c ->
                if (c.moveToFirst()) {
                    val epochCursor = c.getLong(0)
                    open(context, "raw_capture.db")?.use { raw ->
                        raw.rawQuery(
                            "SELECT MAX(id) FROM raw_capture_log WHERE event_type IN ('SCREEN_INTERACTIVE', 'SCREEN_NON_INTERACTIVE')",
                            null,
                        ).use { r ->
                            if (r.moveToFirst() && !r.isNull(0)) {
                                assertTrue("epoch cursor on/after the dangling screen event", epochCursor < r.getLong(0))
                            }
                        }
                    }
                }
            }
        }
        assertSyncMatchesData(context, backend, list)
    }

    private fun assertSyncMatchesData(context: Context, backend: FakeBackend, list: List<Epoch>) {
        val local = runBlocking { RealSyncCursorStore(context).getCanonicalSyncedThrough() }
        val acked = backend.cursor
        if (acked == null) {
            assertEquals("local sync cursor without any ack", null, local)
            return
        }
        assertEquals("local sync cursor vs. backend ack", acked.clientRecordId, local?.epochId)
        val ackedTs = Instant.parse(acked.startTs).toEpochMilli()
        val ackedIds = backend.sentRecords(acknowledgedOnly = true).map { it.first }.toSet()
        val everSent = backend.sentRecords().map { it.first }.toSet()
        list.filter { it.startTs <= ackedTs && !it.skipped }.forEach { assertTrue("epoch ${it.id} before the cursor was never acknowledged", it.id in ackedIds) }
        list.filter { it.startTs > ackedTs }.forEach { assertTrue("epoch ${it.id} after the cursor was acknowledged", it.id !in ackedIds) }
        list.filter { it.skipped }.forEach {
            assertTrue("skipped epoch ${it.id} was acknowledged", it.id !in ackedIds)
            assertTrue("skipped epoch ${it.id} was never named by a 409", it.id in backend.conflicted)
        }
        list.lastOrNull()?.let { assertTrue("the newest epoch (no successor) was sent", it.id !in everSent) }
    }
}
