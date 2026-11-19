package com.example.smartuse_frontend.data.db

import com.example.smartuse_frontend.network.dto.CutoffResponse
import com.example.smartuse_frontend.data.CanonicalSyncCursor
import com.example.smartuse_frontend.data.FakeSyncCursorStore
import com.example.smartuse_frontend.jobs.JobLimits
import com.example.smartuse_frontend.network.FailureKind
import com.example.smartuse_frontend.network.NetworkResult
import com.example.smartuse_frontend.network.SmartUseApi
import com.example.smartuse_frontend.network.dto.CanonicalCursorResponse
import com.example.smartuse_frontend.network.dto.CanonicalSyncRequest
import com.example.smartuse_frontend.network.dto.CanonicalSyncResponse
import com.example.smartuse_frontend.network.dto.EpochConflictDto
import com.example.smartuse_frontend.network.dto.ErrorResponse
import com.example.smartuse_frontend.network.dto.OptOutAckResponse
import com.example.smartuse_frontend.network.dto.RegisterRequest
import com.example.smartuse_frontend.network.dto.RegisterResponse
import com.example.smartuse_frontend.network.dto.SyncedThroughDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private class ScriptedApi(
    private val cursorResponses: MutableList<NetworkResult<CanonicalCursorResponse>>,
    private val postResponses: MutableList<NetworkResult<CanonicalSyncResponse>> = mutableListOf(),
) : SmartUseApi {
    val calls = mutableListOf<String>()
    val syncRequests = mutableListOf<CanonicalSyncRequest>()

    override suspend fun register(body: RegisterRequest): NetworkResult<RegisterResponse> =
        throw NotImplementedError()

    override suspend fun syncCanonical(authToken: String, body: CanonicalSyncRequest): NetworkResult<CanonicalSyncResponse> {
        calls += "POST"
        syncRequests += body
        return postResponses.removeAt(0)
    }

    override suspend fun getCanonicalCursor(authToken: String): NetworkResult<CanonicalCursorResponse> {
        calls += "GET"
        return cursorResponses.removeAt(0)
    }

    override suspend fun getCutoff(): NetworkResult<CutoffResponse> =
        throw NotImplementedError("not exercised by this test class")
    override suspend fun optOutSoft(authToken: String): NetworkResult<OptOutAckResponse> =
        throw NotImplementedError("not exercised by this test class")

    override suspend fun optOutHard(authToken: String): NetworkResult<OptOutAckResponse> =
        throw NotImplementedError("not exercised by this test class")
}

/** Returns the current instant, then moves forward by [step] — simulates time passing between checks. */
private class AdvancingClock(private var now: Instant, private val step: Duration) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant {
        val current = now
        now = now.plus(step)
        return current
    }
}

class CanonicalSyncRunnerTest {

    private val now = Instant.parse("2026-09-20T12:00:00Z")
    private val fixedClock = Clock.fixed(now, ZoneOffset.UTC)
    private val logs = mutableListOf<String>()

    private val cursorA = CanonicalSyncCursor(startTs = 1_000, epochId = "id-a")
    private val cursorB = CanonicalSyncCursor(startTs = 2_000, epochId = "id-b")

    private fun record(startTs: Long, id: String) =
        CanonicalBatchRecord(epochId = id, startTs = startTs, isUse = true, distinctAppCount = 2)

    /** A fake epochs table: `WHERE start_ts > since ORDER BY start_ts LIMIT limit`. */
    private fun table(vararg records: CanonicalBatchRecord): (Long?, Int) -> List<CanonicalBatchRecord> =
        { since, limit -> records.filter { since == null || it.startTs > since }.sortedBy { it.startTs }.take(limit) }

    private fun cursorOk(c: CanonicalSyncCursor?): NetworkResult<CanonicalCursorResponse> =
        NetworkResult.Success(CanonicalCursorResponse(clientRecordId = c?.epochId, startTs = c?.startTs))

    private fun syncOk(c: CanonicalSyncCursor, received: Int = 1): NetworkResult<CanonicalSyncResponse> =
        NetworkResult.Success(
            CanonicalSyncResponse(received = received, syncedThrough = SyncedThroughDto(c.startTs, c.epochId)),
        )

    private fun runner(
        api: ScriptedApi,
        store: FakeSyncCursorStore,
        reader: (Long?, Int) -> List<CanonicalBatchRecord> = { _, _ -> emptyList() },
        batchSize: Int = 500,
        maxBatches: Int = JobLimits.MAX_BATCHES_PER_RUN,
        timeBudget: Duration = JobLimits.RUN_TIME_BUDGET,
        clock: Clock = fixedClock,
        skipEpochs: suspend (List<String>) -> Unit = {},
    ) = CanonicalSyncRunner(
        api = api,
        authToken = "tok",
        cursorStore = store,
        readBatch = reader,
        clock = clock,
        logger = { logs += it },
        batchSize = batchSize,
        maxBatches = maxBatches,
        timeBudget = timeBudget,
        skipEpochs = skipEpochs,
    )

    // --- single batch ----------------------------------------------------------

    @Test
    fun `happy path runs GET, POST, GET and reports the server-verified cursor`() = runBlocking {
        val api = ScriptedApi(mutableListOf(cursorOk(null), cursorOk(cursorB)), mutableListOf(syncOk(cursorB, received = 2)))
        val store = FakeSyncCursorStore()

        val outcome = runner(api, store, table(record(1_500, "x"), record(2_000, "id-b"))).run()

        assertEquals(listOf("GET", "POST", "GET"), api.calls)
        assertEquals(
            CanonicalSyncOutcome.Success(recordsSent = 2, cursorBefore = null, cursorAfter = cursorB, batches = 1),
            outcome,
        )
        assertEquals(cursorB, store.getCanonicalSyncedThrough())
        assertEquals(now, store.getLastSuccessfulCanonicalSyncAt())
    }

    @Test
    fun `batch records are mapped with the small granularity`() = runBlocking {
        val api = ScriptedApi(mutableListOf(cursorOk(null), cursorOk(cursorB)), mutableListOf(syncOk(cursorB)))
        val store = FakeSyncCursorStore()

        runner(api, store, table(record(2_000, "id-b"))).run()

        val sent = api.syncRequests.single().records.single()
        assertEquals("id-b", sent.clientRecordId)
        assertEquals(2_000L, sent.startTs)
        assertEquals(true, sent.isUse)
        assertEquals(2, sent.distinctAppCount)
        assertEquals("small", sent.granularity)
    }

    @Test
    fun `pre-check adopts the server cursor and bounds the batch by it`() = runBlocking {
        val api = ScriptedApi(mutableListOf(cursorOk(cursorB), cursorOk(cursorB)))
        val store = FakeSyncCursorStore(cursor = cursorA)
        var readSince: Long? = -1

        val outcome = runner(api, store, reader = { since, _ -> readSince = since; emptyList() }).run()

        assertEquals(cursorB.startTs, readSince)
        assertEquals(cursorB, store.getCanonicalSyncedThrough())
        assertEquals(cursorB, (outcome as CanonicalSyncOutcome.Success).cursorBefore)
    }

    @Test
    fun `pre-check with a null server cursor clears a stale local cursor`() = runBlocking {
        val api = ScriptedApi(mutableListOf(cursorOk(null), cursorOk(null)))
        val store = FakeSyncCursorStore(cursor = cursorA)
        var readSince: Long? = -1

        runner(api, store, reader = { since, _ -> readSince = since; emptyList() }).run()

        assertNull(readSince)
        assertNull(store.getCanonicalSyncedThrough())
    }

    @Test
    fun `an empty batch skips the POST but still checks the cursor twice`() = runBlocking {
        val api = ScriptedApi(mutableListOf(cursorOk(cursorA), cursorOk(cursorA)))
        val store = FakeSyncCursorStore()

        val outcome = runner(api, store).run()

        assertEquals(listOf("GET", "GET"), api.calls)
        assertEquals(
            CanonicalSyncOutcome.Success(recordsSent = 0, cursorBefore = cursorA, cursorAfter = cursorA, batches = 0),
            outcome,
        )
        // Server confirmed we're current: that counts as a successful sync.
        assertEquals(now, store.getLastSuccessfulCanonicalSyncAt())
    }

    // --- batching ---------------------------------------------------------------

    @Test
    fun `a backlog goes out as several batches, advancing the cursor from each ack`() = runBlocking {
        val r1 = record(1_000, "r1"); val r2 = record(2_000, "r2"); val r3 = record(3_000, "r3")
        val r4 = record(4_000, "r4"); val r5 = record(5_000, "r5")
        val c2 = CanonicalSyncCursor(2_000, "r2"); val c4 = CanonicalSyncCursor(4_000, "r4")
        val c5 = CanonicalSyncCursor(5_000, "r5")
        val api = ScriptedApi(
            mutableListOf(cursorOk(null), cursorOk(c5)),
            mutableListOf(syncOk(c2, 2), syncOk(c4, 2), syncOk(c5, 1)),
        )
        val store = FakeSyncCursorStore()

        val outcome = runner(api, store, table(r1, r2, r3, r4, r5), batchSize = 2).run()

        assertEquals(listOf("GET", "POST", "POST", "POST", "GET"), api.calls)
        assertEquals(listOf(2, 2, 1), api.syncRequests.map { it.records.size })
        assertEquals(listOf("r1", "r3", "r5"), api.syncRequests.map { it.records.first().clientRecordId })
        assertEquals(
            CanonicalSyncOutcome.Success(recordsSent = 5, cursorBefore = null, cursorAfter = c5, batches = 3),
            outcome,
        )
        assertEquals(c5, store.getCanonicalSyncedThrough())
    }

    @Test
    fun `a failure mid-loop keeps the batches already stored and skips the post-check`() = runBlocking {
        val c2 = CanonicalSyncCursor(2_000, "r2")
        val api = ScriptedApi(
            mutableListOf(cursorOk(null)),
            mutableListOf(syncOk(c2, 2), NetworkResult.HttpError(503, null, "unavailable")),
        )
        val store = FakeSyncCursorStore()

        val outcome = runner(
            api, store,
            table(record(1_000, "r1"), record(2_000, "r2"), record(3_000, "r3"), record(4_000, "r4")),
            batchSize = 2,
        ).run()

        assertEquals(listOf("GET", "POST", "POST"), api.calls)
        outcome as CanonicalSyncOutcome.Failed
        assertEquals(FailureKind.TRANSIENT, outcome.kind)
        assertEquals(2, outcome.recordsSentBeforeFailure)
        assertEquals(c2, store.getCanonicalSyncedThrough())
        assertNull(store.getLastSuccessfulCanonicalSyncAt())
    }

    @Test
    fun `a 2xx that does not advance the cursor is a permanent failure, not a loop`() = runBlocking {
        val api = ScriptedApi(mutableListOf(cursorOk(cursorA)), mutableListOf(syncOk(cursorA)))
        val store = FakeSyncCursorStore()

        val outcome = runner(api, store, table(record(1_500, "x"))).run()

        assertEquals(listOf("GET", "POST"), api.calls)
        outcome as CanonicalSyncOutcome.Failed
        assertEquals(FailureKind.PERMANENT, outcome.kind)
        assertTrue(outcome.message, outcome.message.contains("no avanzó"))
        assertEquals(cursorA, store.getCanonicalSyncedThrough())
    }

    @Test
    fun `a 2xx with a null synced_through is a permanent failure`() = runBlocking {
        val api = ScriptedApi(
            mutableListOf(cursorOk(null)),
            mutableListOf(NetworkResult.Success(CanonicalSyncResponse(received = 1, syncedThrough = null))),
        )
        val store = FakeSyncCursorStore()

        val outcome = runner(api, store, table(record(1_500, "x"))).run()

        outcome as CanonicalSyncOutcome.Failed
        assertEquals(FailureKind.PERMANENT, outcome.kind)
    }

    @Test
    fun `the batch cap stops the loop with data remaining and reports hasMore`() = runBlocking {
        val c2 = CanonicalSyncCursor(2_000, "r2"); val c4 = CanonicalSyncCursor(4_000, "r4")
        val api = ScriptedApi(
            mutableListOf(cursorOk(null), cursorOk(c4)),
            mutableListOf(syncOk(c2, 2), syncOk(c4, 2)),
        )
        val store = FakeSyncCursorStore()

        val outcome = runner(
            api, store,
            table(record(1_000, "r1"), record(2_000, "r2"), record(3_000, "r3"), record(4_000, "r4"), record(5_000, "r5")),
            batchSize = 2,
            maxBatches = 2,
        ).run()

        assertEquals(listOf("GET", "POST", "POST", "GET"), api.calls)
        outcome as CanonicalSyncOutcome.Success
        assertEquals(2, outcome.batches)
        assertEquals(4, outcome.recordsSent)
        assertTrue(outcome.hasMore)
    }

    @Test
    fun `the time budget stops the loop between batches`() = runBlocking {
        val c2 = CanonicalSyncCursor(2_000, "r2")
        val api = ScriptedApi(mutableListOf(cursorOk(null), cursorOk(c2)), mutableListOf(syncOk(c2, 2)))
        val store = FakeSyncCursorStore()

        val outcome = runner(
            api, store,
            table(record(1_000, "r1"), record(2_000, "r2"), record(3_000, "r3"), record(4_000, "r4")),
            batchSize = 2,
            timeBudget = Duration.ofMinutes(3),
            clock = AdvancingClock(now, step = Duration.ofMinutes(2)),
        ).run()

        outcome as CanonicalSyncOutcome.Success
        assertEquals(1, outcome.batches)
        assertTrue(outcome.hasMore)
        assertEquals(listOf("GET", "POST", "GET"), api.calls)
    }

    @Test
    fun `batch size must be within 1 and the ceiling`() {
        val api = ScriptedApi(mutableListOf())
        val store = FakeSyncCursorStore()

        assertThrows(IllegalArgumentException::class.java) { runner(api, store, batchSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { runner(api, store, batchSize = JobLimits.SYNC_BATCH_CEILING + 1) }
    }

    // --- failure kinds and messages ------------------------------------------------

    @Test
    fun `pre-check failure aborts before any POST and leaves the local cursor alone`() = runBlocking {
        val api = ScriptedApi(
            mutableListOf(NetworkResult.HttpError(401, ErrorResponse(message = "Unauthorized"), """{"message":"Unauthorized"}""")),
        )
        val store = FakeSyncCursorStore(cursor = cursorA)

        val outcome = runner(api, store, table(record(3_000, "z"))).run()

        assertEquals(listOf("GET"), api.calls)
        outcome as CanonicalSyncOutcome.Failed
        assertEquals(FailureKind.PERMANENT, outcome.kind)
        assertTrue(outcome.message, outcome.message.contains("401"))
        assertTrue(outcome.message, outcome.message.contains("Unauthorized"))
        assertEquals(cursorA, store.getCanonicalSyncedThrough())
    }

    @Test
    fun `a rejected POST fails with status and error code, and keeps the pre-checked cursor`() = runBlocking {
        val api = ScriptedApi(
            mutableListOf(cursorOk(cursorA)),
            mutableListOf(
                NetworkResult.HttpError(
                    409,
                    ErrorResponse(error = "epoch_conflict", message = "start_ts collision"),
                    """{"error":"epoch_conflict","message":"start_ts collision"}""",
                ),
            ),
        )
        val store = FakeSyncCursorStore()

        val outcome = runner(api, store, table(record(3_000, "z"))).run()

        assertEquals(listOf("GET", "POST"), api.calls)
        outcome as CanonicalSyncOutcome.Failed
        assertEquals(FailureKind.PERMANENT, outcome.kind)
        assertTrue(outcome.message, outcome.message.contains("409"))
        assertTrue(outcome.message, outcome.message.contains("epoch_conflict"))
        assertEquals(0, outcome.recordsSentBeforeFailure)
        assertEquals(cursorA, store.getCanonicalSyncedThrough())
        assertNull(store.getLastSuccessfulCanonicalSyncAt())
    }

    @Test
    fun `403 capture_period_ended is classified as study over`() = runBlocking {
        val api = ScriptedApi(
            mutableListOf(cursorOk(cursorA)),
            mutableListOf(NetworkResult.HttpError(403, ErrorResponse(error = "capture_period_ended"), null)),
        )

        val outcome = runner(api, FakeSyncCursorStore(), table(record(3_000, "z"))).run()

        assertEquals(FailureKind.STUDY_OVER, (outcome as CanonicalSyncOutcome.Failed).kind)
    }

    @Test
    fun `a non-JSON error body is still surfaced and a 502 is transient`() = runBlocking {
        val api = ScriptedApi(
            mutableListOf(cursorOk(cursorA)),
            mutableListOf(NetworkResult.HttpError(502, null, "<html>Bad gateway</html>")),
        )

        val outcome = runner(api, FakeSyncCursorStore(), table(record(3_000, "z"))).run()

        outcome as CanonicalSyncOutcome.Failed
        assertEquals(FailureKind.TRANSIENT, outcome.kind)
        assertTrue(outcome.message, outcome.message.contains("502"))
        assertTrue(outcome.message, outcome.message.contains("Bad gateway"))
    }

    @Test
    fun `an IoError is transient and reported with its cause`() = runBlocking {
        val api = ScriptedApi(mutableListOf(NetworkResult.IoError(java.io.IOException("Unparseable response (HTTP 200): nope"))))

        val outcome = runner(api, FakeSyncCursorStore()).run()

        outcome as CanonicalSyncOutcome.Failed
        assertEquals(FailureKind.TRANSIENT, outcome.kind)
        assertTrue(outcome.message, outcome.message.contains("Unparseable response"))
    }

    @Test
    fun `a failed post-check keeps the last POST cursor and reports a verification note`() = runBlocking {
        val api = ScriptedApi(
            mutableListOf(cursorOk(null), NetworkResult.HttpError(500, null, "boom")),
            mutableListOf(syncOk(cursorB)),
        )
        val store = FakeSyncCursorStore()

        val outcome = runner(api, store, table(record(2_000, "id-b"))).run()

        outcome as CanonicalSyncOutcome.Success
        assertEquals(1, outcome.recordsSent)
        assertEquals(cursorB, outcome.cursorAfter)
        assertNotNull(outcome.note)
        assertTrue(outcome.note!!, outcome.note!!.contains("500"))
        assertEquals(cursorB, store.getCanonicalSyncedThrough())
    }

    @Test
    fun `every step is logged`() = runBlocking {
        val api = ScriptedApi(mutableListOf(cursorOk(null), cursorOk(cursorB)), mutableListOf(syncOk(cursorB)))

        runner(api, FakeSyncCursorStore(), table(record(2_000, "id-b"))).run()

        val logged = logs.joinToString("\n")
        assertTrue(logged, logged.contains("pre-check"))
        assertTrue(logged, logged.contains("POST"))
        assertTrue(logged, logged.contains("post-check"))
    }

    // --- 409 epoch_conflict: set the named epochs aside, resend the rest ---------

    /** A fake epochs table that honours `sync_skipped`, like the real batch query. */
    private class SkippableTable(vararg val records: CanonicalBatchRecord) {
        val skipped = mutableListOf<String>()
        val read: (Long?, Int) -> List<CanonicalBatchRecord> = { since, limit ->
            records.filter { it.epochId !in skipped && (since == null || it.startTs > since) }
                .sortedBy { it.startTs }.take(limit)
        }
        val skip: suspend (List<String>) -> Unit = { skipped += it }
    }

    private fun conflict(vararg ids: String): NetworkResult<CanonicalSyncResponse> = NetworkResult.HttpError(
        409,
        ErrorResponse(
            error = "epoch_conflict",
            message = "start_ts collision",
            conflicts = ids.map { EpochConflictDto(clientRecordId = it, startTs = "2026-09-20T10:00:00Z") },
        ),
        "{}",
    )

    private fun fiveRecords() = SkippableTable(
        record(1_000, "a"), record(2_000, "b"), record(3_000, "c"), record(4_000, "d"), record(5_000, "e"),
    )

    @Test
    fun `a collision mid-batch skips only that epoch and stores its neighbours`() = runBlocking {
        val t = fiveRecords()
        val cursorE = CanonicalSyncCursor(5_000, "e")
        val api = ScriptedApi(mutableListOf(cursorOk(null), cursorOk(cursorE)), mutableListOf(conflict("c"), syncOk(cursorE, 4)))
        val store = FakeSyncCursorStore()

        val outcome = runner(api, store, t.read, skipEpochs = t.skip).run()

        assertEquals(listOf("c"), t.skipped)
        assertEquals(listOf("a", "b", "d", "e"), api.syncRequests[1].records.map { it.clientRecordId })
        outcome as CanonicalSyncOutcome.Success
        assertEquals(4, outcome.recordsSent)
        assertEquals(1, outcome.skipped)
        assertEquals(cursorE, store.getCanonicalSyncedThrough())
    }

    @Test
    fun `several collisions in one response are all set aside at once`() = runBlocking {
        val t = fiveRecords()
        val cursorE = CanonicalSyncCursor(5_000, "e")
        val api = ScriptedApi(mutableListOf(cursorOk(null), cursorOk(cursorE)), mutableListOf(conflict("b", "d"), syncOk(cursorE, 3)))

        runner(api, FakeSyncCursorStore(), t.read, skipEpochs = t.skip).run()

        assertEquals(listOf("b", "d"), t.skipped)
        assertEquals(listOf("a", "c", "e"), api.syncRequests[1].records.map { it.clientRecordId })
    }

    @Test
    fun `repeated collisions are resolved one response at a time until the batch is accepted`() = runBlocking {
        val t = fiveRecords()
        val cursorE = CanonicalSyncCursor(5_000, "e")
        val api = ScriptedApi(
            mutableListOf(cursorOk(null), cursorOk(cursorE)),
            mutableListOf(conflict("b"), conflict("d"), syncOk(cursorE, 3)),
        )

        val outcome = runner(api, FakeSyncCursorStore(), t.read, skipEpochs = t.skip).run()

        assertEquals(listOf("b", "d"), t.skipped)
        assertEquals(listOf("a", "c", "e"), api.syncRequests[2].records.map { it.clientRecordId })
        assertEquals(2, (outcome as CanonicalSyncOutcome.Success).skipped)
    }

    @Test
    fun `a collision in a later batch keeps the earlier batches stored`() = runBlocking {
        val t = fiveRecords()
        val cursorB2 = CanonicalSyncCursor(2_000, "b")
        val cursorE = CanonicalSyncCursor(5_000, "e")
        val api = ScriptedApi(
            mutableListOf(cursorOk(null), cursorOk(cursorE)),
            mutableListOf(syncOk(cursorB2, 2), conflict("d"), syncOk(cursorE, 2)),
        )
        val store = FakeSyncCursorStore()

        runner(api, store, t.read, batchSize = 3, skipEpochs = t.skip).run()

        assertEquals(listOf("d"), t.skipped)
        assertEquals(listOf("c", "e"), api.syncRequests[2].records.map { it.clientRecordId })
        assertEquals(cursorE, store.getCanonicalSyncedThrough())
    }

    @Test
    fun `a 409 naming no epoch from the batch fails permanently and skips nothing`() = runBlocking {
        val t = fiveRecords()
        val api = ScriptedApi(mutableListOf(cursorOk(null)), mutableListOf(conflict("not-in-this-batch")))

        val outcome = runner(api, FakeSyncCursorStore(), t.read, skipEpochs = t.skip).run()

        assertEquals(emptyList<String>(), t.skipped)
        outcome as CanonicalSyncOutcome.Failed
        assertEquals(FailureKind.PERMANENT, outcome.kind)
    }

    @Test
    fun `a 409 that is not an epoch conflict stays a permanent failure`() = runBlocking {
        val t = fiveRecords()
        val validation = NetworkResult.HttpError(
            409,
            ErrorResponse(error = "validation_error", conflicts = listOf(EpochConflictDto("c", null))),
            "{}",
        )
        val api = ScriptedApi(mutableListOf(cursorOk(null)), mutableListOf(validation))

        val outcome = runner(api, FakeSyncCursorStore(), t.read, skipEpochs = t.skip).run()

        assertEquals(emptyList<String>(), t.skipped)
        assertEquals(FailureKind.PERMANENT, (outcome as CanonicalSyncOutcome.Failed).kind)
    }

    @Test
    fun `conflict resends count toward the per-run batch cap`() = runBlocking {
        val t = fiveRecords()
        val api = ScriptedApi(mutableListOf(cursorOk(null), cursorOk(null)), mutableListOf(conflict("a")))

        val outcome = runner(api, FakeSyncCursorStore(), t.read, maxBatches = 1, skipEpochs = t.skip).run()

        assertEquals(listOf("GET", "POST", "GET"), api.calls)
        outcome as CanonicalSyncOutcome.Success
        assertTrue(outcome.hasMore)
        assertEquals(0, outcome.recordsSent)
        assertEquals(1, outcome.skipped)
    }
}
