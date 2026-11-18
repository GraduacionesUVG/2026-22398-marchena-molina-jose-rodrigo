package com.example.smartuse_frontend.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpochBuilderTest {

    private fun on(id: Long, ts: Long) = RawEvent(id, ts, "SCREEN_INTERACTIVE", null)
    private fun off(id: Long, ts: Long) = RawEvent(id, ts, "SCREEN_NON_INTERACTIVE", null)
    private fun resumed(id: Long, ts: Long, app: String) = RawEvent(id, ts, "ACTIVITY_RESUMED", app)

    // What the projection does on each run: read the raw rows after the stored
    // cursor (only those captured so far), build epochs, store the new cursor.
    private class Pipeline(private val rawLog: List<RawEvent>) {
        var cursor = 0L
            private set
        val epochs = mutableListOf<BuiltEpoch>()

        fun run(capturedUpToId: Long) {
            val rows = rawLog.filter { it.id > cursor && it.id <= capturedUpToId }
            val result = buildEpochs(rows, cursor)
            epochs += result.epochs
            cursor = result.nextCursor
        }
    }

    @Test
    fun `two screen events close one epoch and the second stays dangling`() {
        val result = buildEpochs(listOf(on(1, 10), off(2, 20)), cursor = 0)

        assertEquals(
            listOf(BuiltEpoch(startTs = 10, isUse = true, closingEventTs = 20, closingEventType = "SCREEN_NON_INTERACTIVE", appHashes = emptyList())),
            result.epochs,
        )
        // The dangling event (id 2) must be read again next run, so the cursor stops just before it.
        assertEquals(1L, result.nextCursor)
    }

    @Test
    fun `a single screen event closes nothing and leaves the cursor alone`() {
        val result = buildEpochs(listOf(on(1, 10)), cursor = 0)

        assertTrue(result.epochs.isEmpty())
        assertEquals(0L, result.nextCursor)
    }

    @Test
    fun `no rows changes nothing`() {
        val result = buildEpochs(emptyList(), cursor = 5)

        assertTrue(result.epochs.isEmpty())
        assertEquals(5L, result.nextCursor)
    }

    @Test
    fun `runs split at any point still form one contiguous timeline`() {
        val log = listOf(on(1, 10), off(2, 20), on(3, 30), off(4, 40), on(5, 50))
        val pipeline = Pipeline(log)

        pipeline.run(capturedUpToId = 3) // ids 1..3: closes [10,20) and [20,30), 30 is dangling
        pipeline.run(capturedUpToId = 5) // ids 3..5: must close [30,40) and [40,50)

        assertEquals(listOf(10L, 20L, 30L, 40L), pipeline.epochs.map { it.startTs })
        assertEquals(listOf(true, false, true, false), pipeline.epochs.map { it.isUse })
    }

    @Test
    fun `interaction events after the dangling event count toward its epoch on the next run`() {
        val log = listOf(on(1, 10), off(2, 20), on(3, 30), resumed(4, 32, "app-x"), off(5, 40))
        val pipeline = Pipeline(log)

        pipeline.run(capturedUpToId = 3)
        pipeline.run(capturedUpToId = 5)

        val useEpoch = pipeline.epochs.single { it.startTs == 30L }
        assertEquals(listOf("app-x"), useEpoch.appHashes)
    }

    @Test
    fun `a run with nothing new after the dangling event does nothing`() {
        val pipeline = Pipeline(listOf(on(1, 10), off(2, 20), on(3, 30)))
        pipeline.run(capturedUpToId = 3)
        val cursorAfterFirstRun = pipeline.cursor
        val epochsAfterFirstRun = pipeline.epochs.toList()

        pipeline.run(capturedUpToId = 3)

        assertEquals(cursorAfterFirstRun, pipeline.cursor)
        assertEquals(epochsAfterFirstRun, pipeline.epochs)
    }

    @Test
    fun `a use epoch lists the distinct apps from its own window, and a non-use epoch none`() {
        val log = listOf(
            on(1, 10),
            resumed(2, 12, "a"),
            resumed(3, 14, "b"),
            resumed(4, 15, "a"),
            off(5, 20),
            resumed(6, 22, "c"),
            on(7, 30),
        )

        val epochs = buildEpochs(log, cursor = 0).epochs

        assertEquals(listOf("a", "b"), epochs[0].appHashes)
        assertTrue(epochs[1].appHashes.isEmpty())
    }
}
