package com.example.smartuse_frontend.forms

/**
 * The end forms on the device: drafts while answering (so leaving the app
 * resumes them), then the frozen copy until the final send is confirmed.
 * Never sent except through POST /final.
 */
interface FormDraftStore {
    suspend fun draft(formId: String): Map<String, FormAnswer>

    /** Saves one answer as it is given; null or blank text removes it. */
    suspend fun saveAnswer(formId: String, questionId: String, answer: FormAnswer?)

    suspend fun completed(): CompletedForms?
    suspend fun saveCompleted(forms: CompletedForms)

    /** Removes the drafts and the frozen copy (after the confirmed final send). */
    suspend fun clear()
}

/** A stored answer: blank text counts as none. */
internal fun FormAnswer?.storable(): FormAnswer? = takeUnless { it is FormAnswer.Text && it.value.isBlank() }

class FakeFormDraftStore : FormDraftStore {
    private val drafts = mutableMapOf<String, Map<String, FormAnswer>>()
    private var frozen: CompletedForms? = null

    override suspend fun draft(formId: String): Map<String, FormAnswer> = drafts[formId].orEmpty()

    override suspend fun saveAnswer(formId: String, questionId: String, answer: FormAnswer?) {
        val current = drafts[formId].orEmpty()
        drafts[formId] = answer.storable()?.let { current + (questionId to it) } ?: (current - questionId)
    }

    override suspend fun completed(): CompletedForms? = frozen

    override suspend fun saveCompleted(forms: CompletedForms) {
        frozen = forms
    }

    override suspend fun clear() {
        drafts.clear()
        frozen = null
    }
}