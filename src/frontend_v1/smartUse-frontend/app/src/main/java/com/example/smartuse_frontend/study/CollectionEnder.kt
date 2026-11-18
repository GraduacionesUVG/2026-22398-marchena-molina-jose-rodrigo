package com.example.smartuse_frontend.study

import android.util.Log
import com.example.smartuse_frontend.jobs.JobStateStore
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.StudyNotifier
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

private const val TAG = "CollectionEnd"

/**
 * The one ending for both triggers (day 21 and the cutoff): mark → cancel →
 * erase → end notice. Safe to run any number of times; only the first erases
 * and notifies. The mark comes first so that a run dying partway still routes
 * to the end screen, and the gate stays closed.
 */
class CollectionEnder(
    private val stateStore: JobStateStore,
    private val cancelAll: () -> Unit,
    private val wipeLocalData: suspend () -> Unit,
    private val notifier: StudyNotifier,
    private val clock: Clock,
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
) {
    suspend fun end() = lock.withLock {
        if (stateStore.collectionEndedAt() != null) {
            cancelAll()
            return@withLock
        }
        // From the mark on, the ending must finish: both real callers cancel
        // themselves partway (cancelAll() stops the calling worker; the launch
        // effect's keys change as soon as the mark lands), and a later run only
        // sees "already ended", so an erase cut short would never be retried.
        withContext(NonCancellable) {
            stateStore.setCollectionEndedAt(clock.instant())
            logger("collection ended: cancelling jobs and erasing local data")
            cancelAll()
            wipeLocalData()
            try {
                notifier.show(StudyNotice.EndOfCollection)
            } catch (e: Exception) {
                logger("end notice failed: ${e::class.java.simpleName}: ${e.message}")
            }
        }
    }

    // Workers and the activity share one process, each with its own instance;
    // one lock for all of them keeps simultaneous triggers from erasing twice.
    private companion object {
        val lock = Mutex()
    }
}
