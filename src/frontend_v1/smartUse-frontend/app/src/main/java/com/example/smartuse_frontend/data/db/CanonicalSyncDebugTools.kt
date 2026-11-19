package com.example.smartuse_frontend.data.db

import android.content.Context
import android.util.Log
import com.example.smartuse_frontend.data.CredentialStore
import com.example.smartuse_frontend.data.RealCredentialStore
import com.example.smartuse_frontend.data.RealSyncCursorStore
import com.example.smartuse_frontend.data.SyncCursorStore
import com.example.smartuse_frontend.jobs.JobLimits
import com.example.smartuse_frontend.network.FailureKind
import com.example.smartuse_frontend.network.SmartUseApi
import com.example.smartuse_frontend.network.SmartUseApiImpl
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

private const val TAG = "CanonicalSync"

// One sync at a time per process: the periodic job, the sync-now job and the
// debug button all come through here, so overlapping runs wait for each other
// (the second then finds nothing left to do). Server idempotency covers the rest.
private val syncLock = Mutex()

// Runs one real sync (cursor pre-check, batched POST /sync/canonical, cursor
// post-check) — see CanonicalSyncRunner. Retry/backoff live in the job layer
// (jobs/JobExecutor), not here.
suspend fun syncCanonicalToBackend(
    context: Context,
    api: SmartUseApi = SmartUseApiImpl(),
    credentialStore: CredentialStore = RealCredentialStore(context),
    syncCursorStore: SyncCursorStore = RealSyncCursorStore(context),
    clock: Clock = Clock.systemUTC(),
    limits: JobLimits = JobLimits.current,
): CanonicalSyncOutcome {
    val authToken = credentialStore.authToken()
        ?: return CanonicalSyncOutcome.Failed("No hay sesión activa.", FailureKind.PERMANENT).also {
            Log.d(TAG, "---- sync skipped: no auth token ----")
        }

    return syncLock.withLock {
        Log.d(TAG, "---- sync start (${limits.describe()}) ----")
        val outcome = CanonicalSyncRunner(
            api = api,
            authToken = authToken,
            cursorStore = syncCursorStore,
            readBatch = { sinceStartTs, limit -> readSyncBatch(context, sinceStartTs, limit) },
            clock = clock,
            logger = { Log.d(TAG, it) },
            batchSize = limits.syncBatchSize,
            skipEpochs = { ids -> markSyncSkipped(context, ids) },
        ).run()
        Log.d(TAG, "---- sync end: $outcome ----")
        outcome
    }
}

// Batch-eligible epochs: after the confirmed-synced cursor, not set aside after
// an epoch_conflict, and excluding the single most-recently-closed epoch (no
// successor yet locally: its end is not final until the next screen event).
// `limit` is an Int we control, so it is inlined into the SQL.
private fun readSyncBatch(context: Context, sinceStartTs: Long?, limit: Int): List<CanonicalBatchRecord> {
    val whereClause = if (sinceStartTs != null) "e1.start_ts > ? AND" else ""
    val args = if (sinceStartTs != null) arrayOf(sinceStartTs.toString()) else emptyArray()

    return CanonicalDbHelper(context).readableDatabase.use { db ->
        db.rawQuery(
            """
            SELECT e1.epoch_id, e1.start_ts, e1.is_use, e1.distinct_app_count
            FROM epochs e1
            WHERE $whereClause e1.sync_skipped = 0 AND EXISTS (
                SELECT 1 FROM epochs e2
                WHERE e2.subject_id = e1.subject_id AND e2.start_ts > e1.start_ts
            )
            ORDER BY e1.start_ts
            LIMIT $limit
            """.trimIndent(),
            args,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CanonicalBatchRecord(
                            epochId = cursor.getString(0),
                            startTs = cursor.getLong(1),
                            isUse = cursor.getInt(2) == 1,
                            distinctAppCount = cursor.getInt(3),
                        ),
                    )
                }
            }
        }
    }
}

// Epochs the backend reported as colliding: kept locally (Data overview still
// shows them) but never sent again.
private fun markSyncSkipped(context: Context, epochIds: List<String>) {
    if (epochIds.isEmpty()) return
    CanonicalDbHelper(context).writableDatabase.use { db ->
        val placeholders = epochIds.joinToString(",") { "?" }
        db.execSQL("UPDATE epochs SET sync_skipped = 1 WHERE epoch_id IN ($placeholders)", epochIds.toTypedArray())
    }
}
