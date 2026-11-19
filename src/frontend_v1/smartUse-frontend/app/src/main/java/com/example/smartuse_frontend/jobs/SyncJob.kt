package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.data.db.CanonicalSyncOutcome

/** Body of the sync job: run the batched canonical sync and translate its outcome. */
class SyncJob(private val runSync: suspend () -> CanonicalSyncOutcome) {
    suspend fun body(): JobOutcome = when (val outcome = runSync()) {
        is CanonicalSyncOutcome.Success -> JobOutcome.Success(note = outcome.note)
        is CanonicalSyncOutcome.Failed -> JobOutcome.Failed(outcome.kind, outcome.message)
    }
}
