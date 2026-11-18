package com.example.smartuse_frontend.study

import com.example.smartuse_frontend.forms.CompletedForm
import com.example.smartuse_frontend.forms.CompletedForms
import com.example.smartuse_frontend.forms.FakeFormDraftStore
import com.example.smartuse_frontend.forms.FormAnswer
import com.example.smartuse_frontend.jobs.FakeJobStateStore
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.StudyNotifier
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class EndingCleanupTest {

    private val now = Instant.parse("2026-12-03T09:00:00Z")
    private val drafts = FakeFormDraftStore()
    private val events = mutableListOf<String>()
    private var failStop = false

    private val notifier = object : StudyNotifier {
        override fun show(notice: StudyNotice) { events += "notice:$notice" }
        override fun clear() { events += "clear" }
    }

    private fun cleanup(store: FakeJobStateStore) = EndingCleanup(
        stateStore = store,
        forms = drafts,
        ensureFinalSend = { events += "send" },
        wipeUsageData = { events += "wipe" },
        stopService = { if (failStop) throw IllegalStateException("boom") else events += "stop" },
        notifier = notifier,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        logger = {},
    )

    private val frozen = CompletedForms(listOf(CompletedForm("usability", 2, buildJsonObject {})))
    private val finishSteps = listOf("wipe", "stop", "clear", "notice:${StudyNotice.Finished(optedOut = false)}")

    @Test
    fun `live or waiting for the forms, nothing happens`() = runTest {
        cleanup(FakeJobStateStore()).ensure()
        cleanup(FakeJobStateStore(collectionEndedAt = now)).ensure()

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `frozen forms without the mark are marked and sent`() = runTest {
        val store = FakeJobStateStore(collectionEndedAt = now)
        drafts.saveCompleted(frozen)

        cleanup(store).ensure()

        assertEquals(now, store.formsCompletedAt())
        assertEquals(listOf("send"), events)
    }

    @Test
    fun `forms done re-ensures the send`() = runTest {
        cleanup(FakeJobStateStore(collectionEndedAt = now, formsCompletedAt = now)).ensure()

        assertEquals(listOf("send"), events)
    }

    @Test
    fun `sent erases, clears the forms, stops the service and posts the end notice, once`() = runTest {
        val store = FakeJobStateStore(collectionEndedAt = now, formsCompletedAt = now, finalSentAt = now)
        drafts.saveAnswer("usability", "d1_1", FormAnswer.Choice(3))
        drafts.saveCompleted(frozen)

        cleanup(store).ensure()
        cleanup(store).ensure()
        cleanup(store).finish()

        assertEquals(finishSteps, events)
        assertNull(drafts.completed())
        assertEquals(emptyMap<String, FormAnswer>(), drafts.draft("usability"))
        assertEquals(now, store.endCleanupDoneAt())
    }

    @Test
    fun `finish before the send is confirmed does nothing`() = runTest {
        cleanup(FakeJobStateStore(collectionEndedAt = now, formsCompletedAt = now)).finish()

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `an opt-out ends with the opt-out notice`() = runTest {
        cleanup(FakeJobStateStore(optedOutAt = now, formsCompletedAt = now, finalSentAt = now)).finish()

        assertEquals("notice:${StudyNotice.Finished(optedOut = true)}", events.last())
    }

    @Test
    fun `a step failing after the send is finished by the next ensure`() = runTest {
        val store = FakeJobStateStore(collectionEndedAt = now, formsCompletedAt = now, finalSentAt = now)
        failStop = true
        runCatching { cleanup(store).finish() }
        assertNull(store.endCleanupDoneAt())

        failStop = false
        events.clear()
        cleanup(store).ensure()

        assertEquals(finishSteps, events)
        assertEquals(now, store.endCleanupDoneAt())
    }
}