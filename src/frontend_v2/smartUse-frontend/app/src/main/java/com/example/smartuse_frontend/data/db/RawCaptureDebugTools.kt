package com.example.smartuse_frontend.data.db

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.smartuse_frontend.data.APP_ATTRIBUTED_EVENT_TYPES
import com.example.smartuse_frontend.data.CAPTURED_EVENT_TYPES
import com.example.smartuse_frontend.data.isCaptured
import com.example.smartuse_frontend.data.CredentialStore
import com.example.smartuse_frontend.data.KeystoreKeyManager
import com.example.smartuse_frontend.data.RealCredentialStore
import com.example.smartuse_frontend.data.RealKeystoreKeyManager
import com.example.smartuse_frontend.data.RealSetupStateRepository
import com.example.smartuse_frontend.data.SetupStateRepository
import java.time.Instant
import com.example.smartuse_frontend.data.usageEventTypeName

private const val TAG = "RawCaptureDebug"

// Only reached if there's no cursor yet AND no signup_ts (e.g. this debug
// button tapped before setup ever completed) — never hit by the real
// pipeline, since setup gates entry to everything else.
private val FALLBACK_WINDOW_MS = 24 * 60 * 60 * 1000L

// TEMP — debug only, manual trigger for what the real 12h capture cycle
// (data-capture-pipeline.md) will eventually do on its own: resume from the
// persisted cursor (bootstrapped from signup_ts on first run), read
// UsageStatsManager from there, keep only the vocabulary events, hash the
// package name at capture time for app-attributed events, append new rows
// (the UNIQUE index makes re-reading the cursor boundary safe), and advance
// the cursor to the max event_ts actually returned — all in one transaction.
suspend fun projectUsageEventsToRawLog(
    context: Context,
    keystoreKeyManager: KeystoreKeyManager = RealKeystoreKeyManager(),
    credentialStore: CredentialStore = RealCredentialStore(context),
    setupState: SetupStateRepository = RealSetupStateRepository(context),
): Int {
    keystoreKeyManager.ensureHmacKey()

    val helper = RawCaptureDbHelper(context)
    val endTime = System.currentTimeMillis()
    val startTime = captureStart(readCursor(helper), setupState.historyStart(), credentialStore.signupTimestamp(), endTime)

    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val events = usageStatsManager.queryEvents(startTime, endTime)
    val event = UsageEvents.Event()

    var inserted = 0
    var maxEventTs = startTime
    helper.writableDatabase.use { db ->
        db.beginTransaction()
        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType !in CAPTURED_EVENT_TYPES) continue
                maxEventTs = maxOf(maxEventTs, event.timeStamp)
                // Our own activities are not participant app use; the cursor still moves past them.
                if (!isCaptured(event.eventType, event.packageName, context.packageName)) continue

                val appHash = if (event.eventType in APP_ATTRIBUTED_EVENT_TYPES) {
                    keystoreKeyManager.hmacHex(event.packageName)
                } else {
                    null
                }

                val rowId = db.insertWithOnConflict(
                    "raw_capture_log",
                    null,
                    ContentValues().apply {
                        put("event_ts", event.timeStamp)
                        put("event_type", usageEventTypeName(event.eventType))
                        put("app_hash", appHash)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (rowId != -1L) inserted++
            }
            writeCursor(db, maxEventTs)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    Log.d(TAG, "---- projected $inserted new event(s) from window [$startTime, $endTime), cursor now $maxEventTs ----")
    return inserted
}

/**
 * Where a capture run reads from: the cursor; on the first run, the consented
 * earlier-usage window (before signup), else signup, else the last 24 h.
 */
fun captureStart(cursorMs: Long?, historyStart: Instant?, signup: Instant?, nowMs: Long): Long =
    cursorMs ?: historyStart?.toEpochMilli() ?: signup?.toEpochMilli() ?: (nowMs - FALLBACK_WINDOW_MS)

private fun readCursor(helper: RawCaptureDbHelper): Long? =
    helper.readableDatabase.use { db ->
        db.rawQuery("SELECT last_event_ts FROM capture_cursor WHERE id = 1", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

// Manual upsert instead of "ON CONFLICT DO UPDATE" — that UPSERT syntax
// needs SQLite 3.24+, not guaranteed on the app's current minSdk 24 devices.
private fun writeCursor(db: SQLiteDatabase, lastEventTs: Long) {
    val values = ContentValues().apply {
        put("id", 1)
        put("last_event_ts", lastEventTs)
    }
    val updated = db.update("capture_cursor", values, "id = 1", null)
    if (updated == 0) {
        db.insert("capture_cursor", null, values)
    }
}

// TEMP — debug only. Logcats the cursor and every row currently in the raw
// capture log.
fun printRawCaptureLog(context: Context) {
    val helper = RawCaptureDbHelper(context)
    Log.d(TAG, "---- capture_cursor: last_event_ts=${readCursor(helper)} ----")
    helper.readableDatabase.use { db ->
        db.rawQuery(
            "SELECT id, event_ts, event_type, app_hash FROM raw_capture_log ORDER BY event_ts",
            null,
        ).use { rows ->
            var rowCount = 0
            Log.d(TAG, "---- raw_capture_log ----")
            while (rows.moveToNext()) {
                rowCount++
                Log.d(
                    TAG,
                    "id=${rows.getInt(0)} event_ts=${rows.getLong(1)} " +
                        "event_type=${rows.getString(2)} app_hash=${rows.getString(3)}",
                )
            }
            Log.d(TAG, "---- $rowCount raw_capture_log row(s) ----")
        }
    }
}
