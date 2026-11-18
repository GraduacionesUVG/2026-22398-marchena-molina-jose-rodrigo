package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.data.db.CanonicalSyncOutcome
import com.example.smartuse_frontend.jobs.JobStateStore
import com.example.smartuse_frontend.notification.HardOptOutStage
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.StudyNotifier
import com.example.smartuse_frontend.network.NetworkResult
import com.example.smartuse_frontend.network.SmartUseApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * Real opt-out orchestration.
 *
 * Both flows write [JobStateStore.setOptedOutAt] synchronously, before any
 * network call, so capture/sync stop locally regardless of connectivity.
 * Soft then drains and acks in the background ([backgroundScope]) and never
 * surfaces a failure — a stuck soft ack is a minor administrative gap, not a
 * broken promise. Hard acks inline and its status stays visible until the
 * backend confirms: "your data will be deleted" is a promise that must not be
 * claimed falsely; the finish worker retries automatically. Hard opt-out first
 * refreshes and checks the cutoff; soft opt-out doesn't.
 */
class RealOptOutRepository(
    private val stateStore: JobStateStore,
    private val credentialStore: CredentialStore,
    private val api: SmartUseApi,
    private val cancelCapture: () -> Unit,
    private val cancelAll: () -> Unit,
    private val runDrainCycle: suspend () -> CanonicalSyncOutcome,
    private val scheduleFinishWorker: () -> Unit,
    private val wipeLocalData: suspend () -> Unit,
    private val notifier: StudyNotifier,
    private val backgroundScope: CoroutineScope,
    private val clock: Clock,
    private val refreshCutoff: suspend () -> Unit = {},
    private val isWindowOpen: suspend () -> Boolean = { true },
    private val endCollection: suspend () -> Unit = {},
    private val logger: (String) -> Unit = {},
) : OptOutRepository {

    // In-memory only: after a process death the worker's retries carry on and the
    // screen shows the plain pending state.
    private val attemptFailed = MutableStateFlow(false)

    override val statusUpdates: Flow<OptOutStatus> =
        combine(stateStore.optOutRecord, attemptFailed) { record, failed ->
            deriveOptOutStatus(record.optedOutAt, record.kind, record.hardDeleteConfirmed, failed)
        }

    override suspend fun status(): OptOutStatus = statusUpdates.first()

    override suspend fun optOutSoft(): OptOutResult {
        // Kind first: MainActivity routes on the time alone.
        stateStore.setOptOutKind(OptOutKind.SOFT)
        stateStore.setOptedOutAt(clock.instant())
        cancelCapture()
        notifier.clear() // soft never shows a status
        backgroundScope.launch { finishSoft() }
        return OptOutResult.Success
    }

    // Non-cancellable: leaving the screen during the cutoff check must never
    // lose a participant's withdrawal.
    override suspend fun optOutHard(): OptOutResult = withContext(NonCancellable) {
        // The one opt-out check against the cutoff: after the end moment the app
        // is ended instead, before any opt-out state is written (terms §2; the
        // participant was warned of the date in advance). Retries never check.
        refreshCutoff()
        if (!isWindowOpen()) {
            logger("opt-out-hard: collection has ended, running the ending instead")
            endCollection()
            return@withContext OptOutResult.CollectionEnded
        }
        // Kind first: MainActivity routes on the time alone.
        stateStore.setOptOutKind(OptOutKind.HARD)
        stateStore.setOptedOutAt(clock.instant())
        cancelAll()
        attemptHardDelete()
        OptOutResult.Success
    }

    // --- soft: best-effort, silent -------------------------------------------

    private suspend fun finishSoft() {
        var outcome = runDrainCycle()
        while (outcome is CanonicalSyncOutcome.Success && outcome.hasMore) {
            outcome = runDrainCycle()
        }
        if (outcome is CanonicalSyncOutcome.Failed) {
            logger("opt-out-soft: drain failed (${outcome.message}), scheduling retry")
            scheduleFinishWorker()
            return
        }

        val authToken = credentialStore.authToken()
        if (authToken == null) {
            logger("opt-out-soft: no auth token, scheduling retry")
            scheduleFinishWorker()
            return
        }
        when (api.optOutSoft(authToken)) {
            is NetworkResult.Success -> {
                cancelAll()
                wipeLocalData()
            }
            else -> {
                logger("opt-out-soft: ack failed, scheduling retry")
                scheduleFinishWorker()
            }
        }
    }

    // --- hard: inline, must confirm -------------------------------------------

    private suspend fun attemptHardDelete() {
        notifier.show(StudyNotice.HardOptOut(HardOptOutStage.PENDING))
        // Queued before the call, not only on failure: if this call is cancelled
        // (screen closed, process killed) the worker still resolves the ack and
        // the notification. KEEP: never duplicated; it exits early once confirmed.
        scheduleFinishWorker()
        val authToken = credentialStore.authToken()
        if (authToken == null) {
            attemptFailed.value = true
            notifier.show(StudyNotice.HardOptOut(HardOptOutStage.FAILED))
            return
        }
        when (val result = api.optOutHard(authToken)) {
            is NetworkResult.Success -> {
                stateStore.setHardDeleteConfirmed(true)
                notifier.show(StudyNotice.HardOptOut(HardOptOutStage.DONE))
                attemptFailed.value = false
                wipeLocalData()
            }
            else -> {
                attemptFailed.value = true
                notifier.show(StudyNotice.HardOptOut(HardOptOutStage.FAILED))
                logger("opt-out-hard: ack failed ($result), the finish worker retries")
            }
        }
    }
}
