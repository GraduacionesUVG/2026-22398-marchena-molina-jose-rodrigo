package com.example.smartuse_frontend.study

import android.util.Log
import com.example.smartuse_frontend.jobs.JobStateStore
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.StudyNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

private const val TAG = "StudyEnd"

/** What ended the study: day 21 or the cutoff, or the participant's opt-out. */
enum class EndTrigger { COLLECTION_END, OPT_OUT }

/**
 * The one ending for every trigger (day 21, the cutoff, the opt-out): mark →
 * cancel the cycle → stop the interventions → the service into reminder mode
 * → the reminder notification. The mark comes first, so a run
 * dying partway still routes to the end forms and keeps the gate closed. Only
 * a LIVE study is ended; any later run only cancels, so the first mark (and
 * its `end_reason`) wins. The forms, the final send and the erase follow from
 * the marks (EndFormsScreen, FinalSendWorker, EndingCleanup).
 */
class StudyEnder(
    private val stateStore: JobStateStore,
    private val cancelAll: () -> Unit,
    private val stopInterventions: suspend () -> Unit,
    private val startService: () -> Unit,
    private val notifier: StudyNotifier,
    private val clock: Clock,
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
) {
    suspend fun end(trigger: EndTrigger = EndTrigger.COLLECTION_END): Unit = lock.withLock {
        if (stateStore.readEndMarks().state != EndingState.LIVE) {
            cancelAll()
            return@withLock
        }
        // From the mark on, the ending must finish: both real callers cancel
        // themselves partway (cancelAll() stops the calling worker; the launch
        // effect's keys change as soon as the mark lands), and a later run only
        // sees "already ended".
        withContext(NonCancellable) {
            val now = clock.instant()
            when (trigger) {
                EndTrigger.COLLECTION_END -> stateStore.setCollectionEndedAt(now)
                EndTrigger.OPT_OUT -> stateStore.setOptedOutAt(now)
            }
            logger("study ended ($trigger): cancelling the cycle, reminder mode")
            cancelAll()
            attempt("stopping the interventions") { stopInterventions() }
            startService()
            attempt("the reminder notice") { notifier.show(StudyNotice.FormsPending) }
        }
    }

    private suspend fun attempt(what: String, step: suspend () -> Unit) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("$what failed: ${e::class.java.simpleName}: ${e.message}")
        }
    }

    // Workers and the activity share one process, each with its own instance;
    // one lock for all of them keeps simultaneous triggers from ending twice.
    private companion object {
        val lock = Mutex()
    }
}