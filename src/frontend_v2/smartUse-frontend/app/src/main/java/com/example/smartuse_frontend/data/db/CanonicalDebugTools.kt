package com.example.smartuse_frontend.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.smartuse_frontend.data.CredentialStore
import com.example.smartuse_frontend.data.RealCredentialStore
import java.security.MessageDigest

private const val TAG = "CanonicalDebug"

// The epoch-building stage of the capture pipeline (run by the capture job
// and by the debug button, through runFullPipeline): read raw_capture_log rows
// since the epoch cursor, pair up consecutive screen boundary events (no
// end_ts/duration_s: epochs are a contiguous timeline, so one epoch's end is
// just the next one's start),
// and write only fully-closed epochs. The trailing dangling epoch (its
// closing event hasn't arrived yet) is left for the next run — the cursor
// never advances past it.
suspend fun projectRawLogToCanonical(
    context: Context,
    credentialStore: CredentialStore = RealCredentialStore(context),
): Int {
    val subjectId = credentialStore.userId() ?: "debug-subject"

    val rawHelper = RawCaptureDbHelper(context)
    val canonicalHelper = CanonicalDbHelper(context)

    val cursor = readEpochCursor(canonicalHelper)
    val rows = rawHelper.readableDatabase.use { db ->
        db.rawQuery(
            "SELECT id, event_ts, event_type, app_hash FROM raw_capture_log WHERE id > ? ORDER BY id",
            arrayOf(cursor.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(RawEvent(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3)))
                }
            }
        }
    }

    val result = buildEpochs(rows, cursor)

    var epochsWritten = 0
    canonicalHelper.writableDatabase.use { db ->
        db.beginTransaction()
        try {
            for (epoch in result.epochs) {
                val epochId = deterministicEpochId(
                    subjectId = subjectId,
                    startTs = epoch.startTs,
                    isUse = epoch.isUse,
                    closingEventTs = epoch.closingEventTs,
                    closingEventType = epoch.closingEventType,
                )
                val epochRowId = db.insertWithOnConflict(
                    "epochs",
                    null,
                    ContentValues().apply {
                        put("epoch_id", epochId)
                        put("subject_id", subjectId)
                        put("start_ts", epoch.startTs)
                        put("is_use", if (epoch.isUse) 1 else 0)
                        put("distinct_app_count", epoch.appHashes.size)
                        put("source", "device_capture")
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                // Only write epoch_apps if the epoch row actually landed —
                // a CONFLICT_IGNORE no-op means this (subject, start_ts)
                // already exists under a different epoch_id.
                if (epochRowId != -1L) {
                    epoch.appHashes.forEach { hash ->
                        db.insertWithOnConflict(
                            "epoch_apps",
                            null,
                            ContentValues().apply {
                                put("epoch_id", epochId)
                                put("app_hash", hash)
                            },
                            SQLiteDatabase.CONFLICT_IGNORE,
                        )
                    }
                    epochsWritten++
                }
            }
            // Advanced regardless of insert conflicts: every pair was examined either way.
            writeEpochCursor(db, result.nextCursor)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    Log.d(TAG, "---- wrote $epochsWritten epoch(s) from ${rows.size} raw row(s), epoch_cursor now ${result.nextCursor} ----")
    return epochsWritten
}

// Deterministic per backend requirement (not HMAC — epoch_id needs no
// secrecy, only idempotency): two independent reconstructions of the same
// underlying raw range must collapse into the same epoch_id instead of
// duplicating. Deliberately excludes anything reconstruction-dependent
// (raw_capture_log.id, wall-clock-at-insert, randomness) — only the
// closing boundary event's own OS-assigned (event_ts, event_type) are used,
// since those are the same across any from-scratch reconstruction. The
// closing boundary is always a screen event in this design, so its
// app_hash is always null and contributes nothing here.
//
// The backend types client_record_id as `format: uuid` (openapi
// CanonicalRecord), so the SHA-256 is truncated to 128 bits and stamped with
// the version-5 / RFC 4122 variant bits: still a pure function of the input,
// but it validates as a UUID.
internal fun deterministicEpochId(
    subjectId: String,
    startTs: Long,
    isUse: Boolean,
    closingEventTs: Long,
    closingEventType: String,
): String {
    val input = "$subjectId|$startTs|$isUse|$closingEventTs|$closingEventType"
    val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
    hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = hash.take(16).joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}

internal fun readEpochCursor(helper: CanonicalDbHelper): Long =
    helper.readableDatabase.use { db ->
        db.rawQuery("SELECT last_raw_log_id FROM epoch_cursor WHERE id = 1", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

// Manual upsert instead of "ON CONFLICT DO UPDATE" — see the matching note
// in RawCaptureDebugTools.kt (needs SQLite 3.24+, not guaranteed on minSdk 24).
private fun writeEpochCursor(db: SQLiteDatabase, lastRawLogId: Long) {
    val values = ContentValues().apply {
        put("id", 1)
        put("last_raw_log_id", lastRawLogId)
    }
    val updated = db.update("epoch_cursor", values, "id = 1", null)
    if (updated == 0) {
        db.insert("epoch_cursor", null, values)
    }
}

// TEMP — debug only. Opens the canonical DB and logcats every epoch joined
// with its epoch_apps rows. derived_end_ts is looked up via a correlated
// subquery rather than a window function (LEAD needs SQLite 3.25+, not
// guaranteed on minSdk 24) — it's null for the most recently written epoch,
// since that epoch's "next" boundary is known but hasn't closed/been
// written as its own row yet. That's expected, not a bug.
fun printCanonicalDb(context: Context) {
    val helper = CanonicalDbHelper(context)
    Log.d(TAG, "---- epoch_cursor: last_raw_log_id=${readEpochCursor(helper)} ----")
    helper.readableDatabase.use { db ->
        Log.d(TAG, "---- epochs (+ epoch_apps) ----")
        db.rawQuery(
            """
            SELECT e1.epoch_id, e1.subject_id, e1.start_ts,
                   (SELECT MIN(e2.start_ts) FROM epochs e2
                    WHERE e2.subject_id = e1.subject_id AND e2.start_ts > e1.start_ts) AS derived_end_ts,
                   e1.is_use, e1.distinct_app_count, e1.source
            FROM epochs e1
            ORDER BY e1.start_ts
            """.trimIndent(),
            null,
        ).use { epochs ->
            var rowCount = 0
            while (epochs.moveToNext()) {
                rowCount++
                val epochId = epochs.getString(0)
                val appHashes = db.rawQuery(
                    "SELECT app_hash FROM epoch_apps WHERE epoch_id = ?",
                    arrayOf(epochId),
                ).use { apps ->
                    generateSequence { if (apps.moveToNext()) apps.getString(0) else null }.toList()
                }
                Log.d(
                    TAG,
                    "epoch_id=$epochId subject_id=${epochs.getString(1)} " +
                        "start_ts=${epochs.getLong(2)} derived_end_ts=${if (epochs.isNull(3)) null else epochs.getLong(3)} " +
                        "is_use=${epochs.getInt(4)} distinct_app_count=${epochs.getInt(5)} " +
                        "source=${epochs.getString(6)} app_hashes=$appHashes",
                )
            }
            Log.d(TAG, "---- $rowCount epoch row(s) ----")
        }
    }
}
