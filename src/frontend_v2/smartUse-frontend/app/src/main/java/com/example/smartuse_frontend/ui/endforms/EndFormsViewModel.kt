package com.example.smartuse_frontend.ui.endforms

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartuse_frontend.forms.CompletedForms
import com.example.smartuse_frontend.forms.FormAnswer
import com.example.smartuse_frontend.forms.FormDefinition
import com.example.smartuse_frontend.forms.FormDraftStore
import com.example.smartuse_frontend.forms.FormSection
import com.example.smartuse_frontend.forms.areComplete
import com.example.smartuse_frontend.forms.freezeForms
import com.example.smartuse_frontend.forms.isComplete
import kotlinx.coroutines.launch

/**
 * The end forms, one page per section, across the forms in order. A page's required questions must be answered to go on
 * (open text never is). Answers are drafted as they are given; "Enviar" freezes them and hands them to [submitter]
 * (FormsSubmitter), after which the ending state swaps the screen.
 */
class EndFormsViewModel(
    private val forms: List<FormDefinition>,
    private val drafts: FormDraftStore,
    private val submitter: suspend (CompletedForms) -> Unit,
) : ViewModel() {

    var loaded by mutableStateOf(false)
        private set
    var page by mutableIntStateOf(0)
        private set
    var submitting by mutableStateOf(false)
        private set

    private val answers = mutableStateMapOf<String, Map<String, FormAnswer>>()

    init {
        viewModelScope.launch {
            forms.forEach { answers[it.id] = drafts.draft(it.id) }
            loaded = true
        }
    }

    private val pages: List<Pair<FormDefinition, FormSection>> = forms.flatMap { form -> form.sections.map { form to it } }

    val pageCount: Int get() = pages.size
    val currentForm: FormDefinition get() = pages[page].first
    val currentSection: FormSection get() = pages[page].second

    /** The first page of its form: where the form's title and intro go. */
    val isFormStart: Boolean get() = page == 0 || pages[page - 1].first != currentForm
    val isLastPage: Boolean get() = page == pages.lastIndex
    val canContinue: Boolean get() = loaded && currentSection.questions.areComplete(answers[currentForm.id].orEmpty())

    fun answerTo(questionId: String): FormAnswer? = answers[currentForm.id]?.get(questionId)

    fun answer(questionId: String, answer: FormAnswer?) {
        val formId = currentForm.id
        val now = answers[formId].orEmpty()
        answers[formId] = if (answer == null) now - questionId else now + (questionId to answer)
        viewModelScope.launch { drafts.saveAnswer(formId, questionId, answer) }
    }

    fun next() {
        if (canContinue && !isLastPage) page++
    }

    fun back() {
        if (page > 0) page--
    }

    fun submit() {
        if (submitting || !isLastPage || !forms.all { it.isComplete(answers[it.id].orEmpty()) }) return
        submitting = true
        val frozen = freezeForms(forms, answers.toMap())
        viewModelScope.launch {
            try {
                submitter(frozen)
            } finally {
                submitting = false
            }
        }
    }
}