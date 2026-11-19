package com.example.smartuse_frontend.ui.endforms

import com.example.smartuse_frontend.forms.CompletedForms
import com.example.smartuse_frontend.forms.FakeFormDraftStore
import com.example.smartuse_frontend.forms.FormAnswer
import com.example.smartuse_frontend.forms.FormDefinitions
import com.example.smartuse_frontend.forms.QuestionKind
import com.example.smartuse_frontend.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EndFormsViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val drafts = FakeFormDraftStore()
    private val submitted = mutableListOf<CompletedForms>()
    private var gate: CompletableDeferred<Unit>? = null

    private fun vm() = EndFormsViewModel(FormDefinitions.END_FORMS, drafts) {
        submitted += it
        gate?.await()
    }

    /** Answers the page's required questions with each one's first option. */
    private fun EndFormsViewModel.answerRequired() = currentSection.questions.filter { it.required }.forEach {
        answer(it.id, FormAnswer.Choice((it.kind as QuestionKind.SingleChoice).options.first().value))
    }

    private fun EndFormsViewModel.fillAllPages() {
        repeat(pageCount - 1) {
            answerRequired()
            next()
        }
        answerRequired()
    }

    @Test
    fun `drafts come back, and each answer is saved as it is given`() = runTest {
        drafts.saveAnswer("usability", "d1_1", FormAnswer.Choice(5))

        val vm = vm()
        advanceUntilIdle()
        vm.answer("d1_2", FormAnswer.Choice(2))
        advanceUntilIdle()

        assertTrue(vm.loaded)
        assertEquals(FormAnswer.Choice(5), vm.answerTo("d1_1"))
        assertEquals(mapOf("d1_1" to FormAnswer.Choice(5), "d1_2" to FormAnswer.Choice(2)), drafts.draft("usability"))
    }

    @Test
    fun `one page per section, twelve in all, the form's start on the first`() = runTest {
        val vm = vm()
        advanceUntilIdle()

        assertEquals(12, vm.pageCount)
        assertEquals(FormDefinitions.USABILITY.sections.first(), vm.currentSection)
        assertTrue(vm.isFormStart)
        vm.answerRequired()
        vm.next()
        assertFalse(vm.isFormStart)
    }

    @Test
    fun `next is enabled only once every required question of the page is answered`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.answer("d1_1", FormAnswer.Choice(3))
        assertFalse(vm.canContinue)
        vm.next()
        assertEquals(0, vm.page)

        vm.answer("d1_2", FormAnswer.Choice(3))
        assertTrue(vm.canContinue)
        vm.next()

        assertEquals(1, vm.page)
        vm.back()
        assertEquals(0, vm.page)
    }

    @Test
    fun `open text never holds a page back`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        repeat(vm.pageCount - 1) { vm.answerRequired(); vm.next() }

        assertTrue(vm.isLastPage)
        assertEquals(listOf("d12_1"), vm.currentSection.questions.map { it.id })
        assertTrue(vm.canContinue)
    }

    @Test
    fun `submitting freezes the form with the valid answers, once`() = runTest {
        gate = CompletableDeferred()
        val vm = vm()
        advanceUntilIdle()
        vm.fillAllPages()
        vm.answer("d12_1", FormAnswer.Text("todo bien"))

        vm.submit()
        vm.submit()
        advanceUntilIdle()

        val usability = submitted.single().forms.single { it.id == "usability" }
        assertEquals("todo bien", usability.answers["d12_1"]!!.jsonPrimitive.content)
        assertEquals(1, usability.answers["d1_1"]!!.jsonPrimitive.int)
        assertEquals(1, usability.answers["d11_1"]!!.jsonPrimitive.int)
        assertTrue(vm.submitting)

        gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.submitting)
    }

    @Test
    fun `no submit before the last page`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.answerRequired()
        vm.submit()
        vm.next()
        vm.submit()
        advanceUntilIdle()

        assertTrue(submitted.isEmpty())
    }
}
