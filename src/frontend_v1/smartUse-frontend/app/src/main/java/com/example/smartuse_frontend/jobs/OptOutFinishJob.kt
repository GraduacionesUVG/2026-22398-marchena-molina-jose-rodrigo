package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.data.CredentialStore
import com.example.smartuse_frontend.data.OptOutKind
import com.example.smartuse_frontend.data.db.CanonicalSyncOutcome
import com.example.smartuse_frontend.notification.HardOptOutStage
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.StudyNotifier
import com.example.smartuse_frontend.network.NetworkResult
import com.example.smartuse_frontend.network.SmartUseApi

/**
 * Resumes a soft or hard opt-out whose backend confirmation previously
 * failed. Deliberately outside JobExecutor/JobGate: this job exists
 * *because* opt-out is pending, so the gate's "opted out -> don't run,
 * cancel everything" would need a carve-out that muddies its one job.
 */
class OptOutFinishJob(
    private val stateStore: JobStateStore,
    private val credentialStore: CredentialStore,
    private val api: SmartUseApi,
    private val runDrainCycle: suspend () -> CanonicalSyncOutcome,
    private val cancelAll: () -> Unit,
    private val wipeLocalData: suspend () -> Unit,
    private val notifier: StudyNotifier,
    private val logger: (String) -> Unit,
) {
    /** `true`: finished (local data wiped). `false`: still not confirmed; the worker retries later. */
    suspend fun run(): Boolean {
        val kind = stateStore.optOutKind() ?: run {
            logger("opt-out-finish: no pending kind, nothing to do")
            return true
        }
        // The in-app attempt may already have confirmed it (and wiped the token).
        if (kind == OptOutKind.HARD && stateStore.hardDeleteConfirmed()) {
            logger("opt-out-finish(hard): already confirmed")
            return true
        }
        val authToken = credentialStore.authToken() ?: run {
            logger("opt-out-finish($kind): no auth token")
            return false
        }

        if (kind == OptOutKind.SOFT) {
            var outcome = runDrainCycle()
            while (outcome is CanonicalSyncOutcome.Success && outcome.hasMore) {
                outcome = runDrainCycle()
            }
            if (outcome is CanonicalSyncOutcome.Failed) {
                logger("opt-out-finish(soft): drain still failing (${outcome.message})")
                return false
            }
        }

        val ack = when (kind) {
            OptOutKind.SOFT -> api.optOutSoft(authToken)
            OptOutKind.HARD -> api.optOutHard(authToken)
        }
        return when (ack) {
            is NetworkResult.Success -> {
                if (kind == OptOutKind.HARD) {
                    stateStore.setHardDeleteConfirmed(true)
                    notifier.show(StudyNotice.HardOptOut(HardOptOutStage.DONE))
                }
                cancelAll()
                wipeLocalData()
                true
            }
            else -> {
                // Never overwrite a DONE the in-app retry posted while this call was in flight.
                if (kind == OptOutKind.HARD && stateStore.hardDeleteConfirmed()) return true
                if (kind == OptOutKind.HARD) notifier.show(StudyNotice.HardOptOut(HardOptOutStage.FAILED))
                logger("opt-out-finish($kind): ack still failing ($ack)")
                false
            }
        }
    }
}
