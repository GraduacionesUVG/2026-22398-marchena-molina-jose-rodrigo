package com.example.smartuse_frontend.forms

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeFormDraftStoreTest {

    @Test
    fun `answers are saved one by one, replaced, and removed by null or blank text`() = runTest {
        val store = FakeFormDraftStore()

        store.saveAnswer("usability", "u1", FormAnswer.Choice(2))
        store.saveAnswer("usability", "u1", FormAnswer.Choice(4))
        store.saveAnswer("usability", "u6", FormAnswer.Text("hola"))
        store.saveAnswer("usability", "u6", FormAnswer.Text("  "))
        store.saveAnswer("other", "q1", FormAnswer.Choice(3))
        store.saveAnswer("other", "q1", null)

        assertEquals(mapOf("u1" to FormAnswer.Choice(4)), store.draft("usability"))
        assertEquals(emptyMap<String, FormAnswer>(), store.draft("other"))
    }

    @Test
    fun `clear removes the drafts and the frozen copy`() = runTest {
        val store = FakeFormDraftStore()
        store.saveAnswer("usability", "d1_1", FormAnswer.Choice(3))
        store.saveCompleted(CompletedForms(listOf(CompletedForm("usability", 2, buildJsonObject {}))))

        store.clear()

        assertEquals(emptyMap<String, FormAnswer>(), store.draft("usability"))
        assertNull(store.completed())
    }
}