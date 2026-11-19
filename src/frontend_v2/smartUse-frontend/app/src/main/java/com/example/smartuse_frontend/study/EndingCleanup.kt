package com.example.smartuse_frontend.study

import com.example.smartuse_frontend.forms.FormDraftStore
import com.example.smartuse_frontend.jobs.JobStateStore
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.StudyNotifier
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * Moves the ending on from wherever a process death left it and runs the steps after the confirmed final send: erase the
 * usage data, clear the forms, stop the service, the end notice. Idempotent;
 * called at launch, boot, service start and by the final send itself. The
 * metrics (metrics.db) are never erased.
 */
class EndingCleanup(
    private val stateStore: JobStateStore,
    private val forms: FormDraftStore,
    private val ensureFinalSend: () -> Unit,
    private val wipeUsageData: suspend () -> Unit,
    private val stopService: () -> Unit,
    private val notifier: StudyNotifier,
    private val clock: Clock,
    private val logger: (String) -> Unit,
) {
    suspend fun ensure() = lock.withLock {
        when (stateStore.readEndMarks().state) {
            EndingState.LIVE -> Unit
            // "Enviar" stored the frozen copy and died before the mark.
            EndingState.ENDED -> if (forms.completed() != null) {
                logger("ending: frozen forms without the mark, marking and sending")
                stateStore.setFormsCompletedAt(clock.instant())
                ensureFinalSend()
            }
            EndingState.FORMS_DONE -> ensureFinalSend()
            EndingState.SENT -> finishLocked()
        }
    }

    /** The steps after the confirmed final send; once (endCleanupDoneAt), and only once SENT. */
    suspend fun finish() = lock.withLock { finishLocked() }

    private suspend fun finishLocked() {
        val marks = stateStore.readEndMarks()
        if (marks.state != EndingState.SENT || stateStore.endCleanupDoneAt() != null) return
        withContext(NonCancellable) {
            wipeUsageData()
            forms.clear()
            stopService()
            notifier.clear()
            notifier.show(StudyNotice.Finished(optedOut = marks.reason == EndReason.OPT_OUT))
            stateStore.setEndCleanupDoneAt(clock.instant())
            logger("ending finished: usage data erased, service stopped")
        }
    }

    // The final-send worker and a launch can both get here at once.
    private companion object {
        val lock = Mutex()
    }
}