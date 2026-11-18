package com.example.smartuse_frontend.forms

import com.example.smartuse_frontend.jobs.JobStateStore
import com.example.smartuse_frontend.study.EndingState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * "Enviar": the frozen copy first, then the mark, then the
 * final send. A process death between the first two is healed by
 * EndingCleanup (frozen forms without the mark). Only while ENDED.
 */
class FormsSubmitter(
    private val drafts: FormDraftStore,
    private val stateStore: JobStateStore,
    private val ensureFinalSend: () -> Unit,
    private val clock: Clock,
) {
    suspend fun submit(forms: CompletedForms) = withContext(NonCancellable) {
        if (stateStore.readEndMarks().state != EndingState.ENDED) return@withContext
        drafts.saveCompleted(forms)
        stateStore.setFormsCompletedAt(clock.instant())
        ensureFinalSend()
    }
}