package com.example.smartuse_frontend.study

import com.example.smartuse_frontend.jobs.FakeJobStateStore
import com.example.smartuse_frontend.jobs.JobStateStore
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.StudyNotifier
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StudyEnderTest {

    private val now = Instant.parse("2026-12-01T06:30:00Z")
    private val store = FakeJobStateStore()
    private val events = mutableListOf<String>()

    private val recordingNotifier = object : StudyNotifier {
        override fun show(notice: StudyNotice) { events += "notice:$notice" }
        override fun clear() { events += "clear" }
    }

    private fun ender(
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
        notifier: StudyNotifier = recordingNotifier,
        stateStore: JobStateStore = store,
        stopInterventions: suspend () -> Unit = { events += "interventions" },
    ) = StudyEnder(
        stateStore = stateStore,
        cancelAll = { events += "cancel(state=${runBlocking { store.readEndMarks().state }})" },
        stopInterventions = stopInterventions,
        startService = { events += "service" },
        notifier = notifier,
        clock = clock,
        logger = {},
    )

    private val fullEnding = listOf("cancel(state=ENDED)", "interventions", "service", "notice:${StudyNotice.FormsPending}")

    @Test
    fun `day 21 or the cutoff marks the end, then cancels, stops the interventions, starts reminder mode and reminds`() = runTest {
        ender().end()

        assertEquals(now, store.collectionEndedAt())
        assertNull(store.optedOutAt())
        assertEquals(fullEnding, events)
    }

    @Test
    fun `the opt-out runs the same ending with its own mark`() = runTest {
        ender().end(EndTrigger.OPT_OUT)

        assertEquals(now, store.optedOutAt())
        assertNull(store.collectionEndedAt())
        assertEquals(EndReason.OPT_OUT, store.readEndMarks().reason)
        assertEquals(fullEnding, events)
    }

    @Test
    fun `a second run only cancels - the first mark and its reason stay`() = runTest {
        ender().end()
        events.clear()

        ender(clock = Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC)).end(EndTrigger.OPT_OUT)

        assertEquals(listOf("cancel(state=ENDED)"), events)
        assertEquals(now, store.collectionEndedAt())
        assertNull(store.optedOutAt())
        assertEquals(EndReason.COMPLETED, store.readEndMarks().reason)
    }

    @Test
    fun `after the forms or the send a trigger changes nothing`() = runTest {
        val done = FakeJobStateStore(collectionEndedAt = now, formsCompletedAt = now)

        StudyEnder(
            stateStore = done,
            cancelAll = { events += "cancel" },
            stopInterventions = { events += "interventions" },
            startService = { events += "service" },
            notifier = recordingNotifier,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            logger = {},
        ).end(EndTrigger.OPT_OUT)

        assertEquals(listOf("cancel"), events)
        assertNull(done.optedOutAt())
    }

    @Test
    fun `a failing notice or intervention stop still marks, starts the service and never throws`() = runTest {
        val failing = object : StudyNotifier {
            override fun show(notice: StudyNotice) = throw IllegalStateException("no notifications")
            override fun clear() = Unit
        }

        ender(notifier = failing, stopInterventions = { throw IllegalStateException("no main thread") }).end()

        assertEquals(listOf("cancel(state=ENDED)", "service"), events)
        assertEquals(now, store.collectionEndedAt())
    }

    @Test
    fun `two triggers at once end only once`() = runTest {
        // A worker and an app launch in the same process, each with its own
        // instance. The slow read lets both check the state before either marks it.
        val slowStore = object : JobStateStore by store {
            override suspend fun readEndMarks() = store.readEndMarks().also { delay(10) }
        }

        launch { ender(stateStore = slowStore).end() }
        launch { ender(stateStore = slowStore).end(EndTrigger.OPT_OUT) }
        advanceUntilIdle()

        assertEquals(1, events.count { it.startsWith("notice") })
        assertNull(store.optedOutAt())
    }

    // Both real callers cancel themselves mid-ending: cancelAll() stops the calling
    // worker, and the launch effect's keys change as soon as the mark lands.
    @Test
    fun `a caller cancelled while the end is being marked still finishes the ending`() = runTest {
        val slowMark = object : JobStateStore by store {
            override suspend fun setCollectionEndedAt(instant: Instant) {
                store.setCollectionEndedAt(instant)
                delay(1_000)
            }
        }
        val caller = launch { ender(stateStore = slowMark).end() }
        advanceTimeBy(500)

        caller.cancelAndJoin()
        advanceUntilIdle()

        assertEquals(fullEnding, events)
    }
}