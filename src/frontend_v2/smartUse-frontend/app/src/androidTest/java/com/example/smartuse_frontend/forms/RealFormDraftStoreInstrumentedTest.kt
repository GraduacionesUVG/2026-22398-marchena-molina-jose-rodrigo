package com.example.smartuse_frontend.forms

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealFormDraftStoreInstrumentedTest {

    private val store = RealFormDraftStore(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun draftsAndTheFrozenCopyPersistUntilCleared() = runBlocking {
        store.saveAnswer("usability", "u1", FormAnswer.Choice(4))
        store.saveAnswer("usability", "u6", FormAnswer.Text("bien"))
        val frozen = CompletedForms(listOf(CompletedForm("usability", 2, buildJsonObject { put("d1_1", 3) })))
        store.saveCompleted(frozen)

        assertEquals(mapOf("u1" to FormAnswer.Choice(4), "u6" to FormAnswer.Text("bien")), store.draft("usability"))
        assertEquals(frozen, store.completed())

        store.clear()

        assertEquals(emptyMap<String, FormAnswer>(), store.draft("usability"))
        assertNull(store.completed())
    }
}