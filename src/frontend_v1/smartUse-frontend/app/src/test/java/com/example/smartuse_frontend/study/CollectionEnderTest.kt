package com.example.smartuse_frontend.study

import com.example.smartuse_frontend.jobs.FakeJobStateStore
import com.example.smartuse_frontend.jobs.JobStateStore
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.StudyNotifier
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CollectionEnderTest {

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
        wipe: suspend () -> Unit = {},
        stateStore: JobStateStore = store,
    ) = CollectionEnder(
        stateStore = stateStore,
        cancelAll = { events += "cancel" },
        wipeLocalData = {
            events += "erase(marked=${store.collectionEndedAt() == now})"
            wipe()
        },
        notifier = notifier,
        clock = clock,
        logger = {},
    )

    @Test
    fun `marks the end, then cancels, erases and posts the end notice, in that order`() = runTest {
        ender().end()

        assertEquals(now, store.collectionEndedAt())
        assertEquals(listOf("cancel", "erase(marked=true)", "notice:${StudyNotice.EndOfCollection}"), events)
    }

    @Test
    fun `a second run only cancels - no second erase, no second notice, the first end moment kept`() = runTest {
        ender().end()
        events.clear()

        ender(clock = Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC)).end()

        assertEquals(listOf("cancel"), events)
        assertEquals(now, store.collectionEndedAt())
    }

    @Test
    fun `a failing notice still leaves everything erased and never throws`() = runTest {
        val failing = object : StudyNotifier {
            override fun show(notice: StudyNotice) = throw IllegalStateException("no notifications")
            override fun clear() = Unit
        }

        ender(notifier = failing).end()

        assertEquals(listOf("cancel", "erase(marked=true)"), events)
        assertEquals(now, store.collectionEndedAt())
    }

    @Test
    fun `two triggers at once erase and notify only once`() = runTest {
        // A capture worker and an app launch in the same process, each with its own
        // instance. The slow read lets both check "already ended?" before either marks it.
        val slowStore = object : JobStateStore by store {
            override suspend fun collectionEndedAt(): Instant? = store.collectionEndedAt().also { delay(10) }
        }
        val first = ender(wipe = { delay(1_000) }, stateStore = slowStore)
        val second = ender(wipe = { delay(1_000) }, stateStore = slowStore)

        launch { first.end() }
        launch { second.end() }
        advanceUntilIdle()

        assertEquals(1, events.count { it.startsWith("erase") })
        assertEquals(1, events.count { it.startsWith("notice") })
    }

    // Both real callers cancel themselves mid-ending: cancelAll() stops the calling
    // worker, and the launch effect's keys change as soon as the mark and the erase land.
    @Test
    fun `a caller cancelled mid-erase still finishes the erase and posts the notice`() = runTest {
        val caller = launch {
            ender(wipe = {
                delay(1_000)
                events += "erase done"
            }).end()
        }
        advanceTimeBy(500)

        caller.cancelAndJoin()
        advanceUntilIdle()

        assertEquals(listOf("cancel", "erase(marked=true)", "erase done", "notice:${StudyNotice.EndOfCollection}"), events)
    }

    @Test
    fun `a caller cancelled while the end is being marked still erases and notifies`() = runTest {
        // DataStore commits the mark, then the caller is cancelled before edit() resumes.
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

        assertEquals(listOf("cancel", "erase(marked=true)", "notice:${StudyNotice.EndOfCollection}"), events)
    }
}
