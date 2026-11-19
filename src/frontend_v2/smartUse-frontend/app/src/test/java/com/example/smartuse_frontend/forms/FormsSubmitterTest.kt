package com.example.smartuse_frontend.forms

import com.example.smartuse_frontend.jobs.FakeJobStateStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FormsSubmitterTest {

    private val now = Instant.parse("2026-12-02T10:00:00Z")
    private val forms = CompletedForms(listOf(CompletedForm("usability", 2, buildJsonObject {})))

    @Test
    fun `submitting freezes the forms, then marks them done, then schedules the send`() = runTest {
        val store = FakeJobStateStore(collectionEndedAt = now)
        val drafts = FakeFormDraftStore()
        val events = mutableListOf<String>()

        FormsSubmitter(
            drafts = drafts,
            stateStore = store,
            ensureFinalSend = {
                events += "send(frozen=${runBlocking { drafts.completed() } != null}, " +
                    "state=${runBlocking { store.readEndMarks().state }})"
            },
            clock = Clock.fixed(now, ZoneOffset.UTC),
        ).submit(forms)

        assertEquals(forms, drafts.completed())
        assertEquals(now, store.formsCompletedAt())
        assertEquals(listOf("send(frozen=true, state=FORMS_DONE)"), events)
    }

    @Test
    fun `while live a submit changes nothing`() = runTest {
        val store = FakeJobStateStore()
        val drafts = FakeFormDraftStore()
        var sends = 0

        FormsSubmitter(drafts, store, { sends++ }, Clock.fixed(now, ZoneOffset.UTC)).submit(forms)

        assertNull(drafts.completed())
        assertNull(store.formsCompletedAt())
        assertEquals(0, sends)
    }

    @Test
    fun `a second submit after the forms are done changes nothing`() = runTest {
        val store = FakeJobStateStore(collectionEndedAt = now, formsCompletedAt = now)
        val drafts = FakeFormDraftStore()
        var sends = 0

        FormsSubmitter(drafts, store, { sends++ }, Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)).submit(forms)

        assertNull(drafts.completed())
        assertEquals(now, store.formsCompletedAt())
        assertEquals(0, sends)
    }
}