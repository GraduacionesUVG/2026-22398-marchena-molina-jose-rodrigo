package com.example.smartuse_frontend.data.db

import com.example.smartuse_frontend.data.CanonicalSyncCursor
import com.example.smartuse_frontend.data.SyncCursorStore
import com.example.smartuse_frontend.data.describeCursor
import com.example.smartuse_frontend.jobs.JobLimits
import com.example.smartuse_frontend.network.FailureKind
import com.example.smartuse_frontend.network.NetworkResult
import com.example.smartuse_frontend.network.SmartUseApi
import com.example.smartuse_frontend.network.dto.CanonicalCursorResponse
import com.example.smartuse_frontend.network.dto.CanonicalRecordDto
import com.example.smartuse_frontend.network.dto.CanonicalSyncRequest
import com.example.smartuse_frontend.network.failureKind
import java.time.Clock
import java.time.Duration

sealed interface CanonicalSyncOutcome {
    /**
     * [cursorBefore] is what the server reported before the attempt,
     * [cursorAfter] what it reports afterwards (the last POST's own watermark if
     * the final check couldn't run, in which case [note] says why). [hasMore] is
     * true when the batch cap or time budget stopped the loop with data left.
     */
    data class Success(
        val recordsSent: Int,
        val cursorBefore: CanonicalSyncCursor?,
        val cursorAfter: CanonicalSyncCursor?,
        val note: String? = null,
        val batches: Int = 0,
        val hasMore: Boolean = false,
        /** Epochs set aside this run because the backend reported them as `epoch_conflict`. */
        val skipped: Int = 0,
    ) : CanonicalSyncOutcome

    /** [recordsSentBeforeFailure]: batches already stored by the server before this failure. */
    data class Failed(
        val message: String,
        val kind: FailureKind,
        val recordsSentBeforeFailure: Int = 0,
    ) : CanonicalSyncOutcome
}

internal data class CanonicalBatchRecord(
    val epochId: String,
    val startTs: Long,
    val isUse: Boolean,
    val distinctAppCount: Int,
)

private const val GET_CURSOR = "GET /sync/canonical/cursor"
private const val POST_CANONICAL = "POST /sync/canonical"

/**
 * One canonical sync run, bracketed by cursor checks and driven by the
 * server-confirmed cursor:
 *
 * 1. **pre-check** — `GET /sync/canonical/cursor`. The server is authoritative
 *    (even if behind the local marker); its answer replaces the local marker
 *    (cleared if the server has nothing) and bounds the first batch. A failure
 *    aborts the run.
 * 2. **batches** — repeatedly read up to [batchSize] eligible epochs after the
 *    cursor and POST them; each 2xx moves the cursor to the server's
 *    `synced_through`. Stops when nothing is left, on a failure (progress so far
 *    is kept), when a 2xx fails to advance the cursor (permanent failure), or at
 *    [maxBatches] / [timeBudget] (success with `hasMore`).
 * 3. **post-check** — `GET /sync/canonical/cursor` once, after a clean stop.
 *
 * A `409 epoch_conflict` names the colliding records in `conflicts`: those
 * epochs are set aside for good ([skipEpochs], `sync_skipped` in the DB) and
 * the batch is re-read and resent without them, until it's accepted. Each
 * resend counts toward [maxBatches]. A 409 that names nothing from the batch
 * stays a permanent failure, so a misbehaving backend can't loop forever.
 */
class CanonicalSyncRunner internal constructor(
    private val api: SmartUseApi,
    private val authToken: String,
    private val cursorStore: SyncCursorStore,
    private val readBatch: (sinceStartTs: Long?, limit: Int) -> List<CanonicalBatchRecord>,
    private val clock: Clock,
    private val logger: (String) -> Unit,
    private val batchSize: Int,
    private val maxBatches: Int = JobLimits.MAX_BATCHES_PER_RUN,
    private val timeBudget: Duration = JobLimits.RUN_TIME_BUDGET,
    private val skipEpochs: suspend (List<String>) -> Unit = {},
) {
    init {
        require(batchSize in 1..JobLimits.SYNC_BATCH_CEILING) {
            "batchSize must be in 1..${JobLimits.SYNC_BATCH_CEILING}, was $batchSize"
        }
    }

    suspend fun run(): CanonicalSyncOutcome {
        val startedAt = clock.instant()

        // 1. pre-check
        val before = when (val result = api.getCanonicalCursor(authToken)) {
            is NetworkResult.Success -> result.body.toCursor()
            else -> return failed("pre-check", "verificación previa", GET_CURSOR, result, recordsSent = 0)
        }
        logger("pre-check: server cursor = ${describeCursor(before)}")
        adopt(before)

        // 2. batches
        var cursor = before
        var recordsSent = 0
        var batches = 0
        var posts = 0
        var skipped = 0
        var hasMore = false
        while (true) {
            val batch = readBatch(cursor?.startTs, batchSize)
            if (batch.isEmpty()) {
                logger("POST: nothing more to send after the server cursor")
                break
            }
            if (posts >= maxBatches || Duration.between(startedAt, clock.instant()) >= timeBudget) {
                hasMore = true
                logger("POST: stopping after $batches batch(es) with data remaining (batch cap or time budget); the next run continues")
                break
            }

            logger("POST: batch ${batches + 1}, ${batch.size} record(s) with start_ts > ${cursor?.startTs}")
            val request = CanonicalSyncRequest(
                records = batch.map {
                    CanonicalRecordDto(
                        clientRecordId = it.epochId,
                        startTs = it.startTs,
                        isUse = it.isUse,
                        distinctAppCount = it.distinctAppCount,
                        granularity = "small",
                    )
                },
            )
            posts++
            when (val result = api.syncCanonical(authToken, request)) {
                is NetworkResult.Success -> {
                    val posted = result.body.syncedThrough?.let { CanonicalSyncCursor(it.startTs, it.clientRecordId) }
                    logger("POST: ok, received=${result.body.received}, synced_through = ${describeCursor(posted)}")
                    if (posted == null || (cursor != null && posted.startTs <= cursor.startTs)) {
                        return noProgress(recordsSent, posted)
                    }
                    cursorStore.setCanonicalSyncedThrough(posted)
                    cursor = posted
                    recordsSent += batch.size
                    batches++
                }
                else -> {
                    val colliding = collidingIds(result, batch)
                    if (colliding.isEmpty()) return failed("POST", "POST", POST_CANONICAL, result, recordsSent)
                    batch.filter { it.epochId in colliding }.forEach {
                        logger("POST: epoch_conflict — setting aside epoch ${it.epochId} (start_ts ${it.startTs}), never to be sent")
                    }
                    skipEpochs(colliding)
                    skipped += colliding.size
                }
            }
        }

        // 3. post-check
        var note: String? = null
        var cursorAfter = cursor
        when (val result = api.getCanonicalCursor(authToken)) {
            is NetworkResult.Success -> {
                val confirmed = result.body.toCursor()
                logger("post-check: server cursor = ${describeCursor(confirmed)}")
                if (confirmed != cursorAfter) {
                    logger("post-check: differs from what the last POST reported (${describeCursor(cursorAfter)}) — trusting the server's fresh read")
                }
                if (confirmed != null) {
                    cursorStore.setCanonicalSyncedThrough(confirmed)
                    cursorAfter = confirmed
                }
            }
            else -> {
                note = "Verificación posterior fallida ($GET_CURSOR): ${describeFailure(result)}"
                logger("post-check: failed — $note")
            }
        }

        cursorStore.setLastSuccessfulCanonicalSyncAt(clock.instant())
        return CanonicalSyncOutcome.Success(recordsSent, before, cursorAfter, note, batches, hasMore, skipped)
    }

    private suspend fun adopt(serverCursor: CanonicalSyncCursor?) {
        if (serverCursor == null) cursorStore.clearCanonicalSyncedThrough()
        else cursorStore.setCanonicalSyncedThrough(serverCursor)
    }

    private fun failed(
        step: String,
        stepEs: String,
        endpoint: String,
        result: NetworkResult<*>,
        recordsSent: Int,
    ): CanonicalSyncOutcome.Failed {
        val message = "$endpoint ($stepEs): ${describeFailure(result)}"
        logger("$step: failed — $message")
        return CanonicalSyncOutcome.Failed(message, result.failureKind(), recordsSent)
    }

    // A 2xx that leaves the cursor where it was (or returns none) means the
    // backend did not store what we sent: never loop on that, surface it.
    private fun noProgress(recordsSent: Int, posted: CanonicalSyncCursor?): CanonicalSyncOutcome.Failed {
        val message = "$POST_CANONICAL (POST): el servidor no avanzó el cursor (respuesta: ${describeCursor(posted)})"
        logger("POST: failed — $message")
        return CanonicalSyncOutcome.Failed(message, FailureKind.PERMANENT, recordsSent)
    }
}

// The batch's epoch ids a `409 epoch_conflict` names; empty for any other failure.
private fun collidingIds(result: NetworkResult<*>, batch: List<CanonicalBatchRecord>): List<String> {
    if (result !is NetworkResult.HttpError || result.code != 409) return emptyList()
    if (result.errorBody?.error != "epoch_conflict") return emptyList()
    val named = result.errorBody.conflicts.orEmpty().mapNotNull { it.clientRecordId }.toSet()
    return batch.map { it.epochId }.filter { it in named }
}

private fun CanonicalCursorResponse.toCursor(): CanonicalSyncCursor? =
    if (clientRecordId != null && startTs != null) CanonicalSyncCursor(startTs, clientRecordId) else null

// "HTTP 409 epoch_conflict: start_ts collision", or the raw body when the
// server's error isn't in the documented shape (e.g. an HTML 502 page).
private fun describeFailure(result: NetworkResult<*>): String = when (result) {
    is NetworkResult.HttpError -> {
        val error = result.errorBody?.error
        val message = result.errorBody?.message
        if (error == null && message == null) {
            "HTTP ${result.code}" + (result.rawBody?.takeIf { it.isNotBlank() }?.let { ": ${it.take(200)}" } ?: "")
        } else {
            "HTTP ${result.code}" + (error?.let { " $it" } ?: "") + (message?.let { ": $it" } ?: "")
        }
    }
    is NetworkResult.IoError -> "sin respuesta utilizable: ${result.cause.message ?: result.cause::class.java.simpleName}"
    is NetworkResult.Success -> "inesperado"
}
