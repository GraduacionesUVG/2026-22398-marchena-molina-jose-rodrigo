package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.data.OptOutKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

interface JobStateStore {
    suspend fun get(job: JobName): JobState
    suspend fun put(job: JobName, state: JobState)

    /** Read by JobGate; written by the opt-out flow, before any network call. */
    suspend fun optedOutAt(): Instant?
    suspend fun setOptedOutAt(instant: Instant)

    /** Reactive mirror of [optedOutAt] != null, for top-level nav routing (MainActivity). */
    val hasOptedOut: Flow<Boolean>

    suspend fun optOutKind(): OptOutKind?
    suspend fun setOptOutKind(kind: OptOutKind)

    /** Hard opt-out only; meaningless (and unread) for soft. */
    suspend fun hardDeleteConfirmed(): Boolean
    suspend fun setHardDeleteConfirmed(confirmed: Boolean)

    /** The study-wide cutoff as last fetched from GET /cutoff; null = none published. Survives the local erase. */
    suspend fun cutoffDate(): LocalDate?
    suspend fun setCutoffDate(date: LocalDate?)

    /** Set once, by CollectionEnder, when collection ends (day 21 or the cutoff). Survives the local erase. */
    suspend fun collectionEndedAt(): Instant?
    suspend fun setCollectionEndedAt(instant: Instant)

    /** Reactive mirror of [collectionEndedAt] != null, for top-level nav routing (MainActivity). */
    val collectionEnded: Flow<Boolean>

    /**
     * Resets every job's health record, as part of the local data wipe (opt-out
     * or end of collection). Does **not** touch [optedOutAt]/[optOutKind]/
     * [hardDeleteConfirmed]/[cutoffDate]/[collectionEndedAt]: those must survive
     * to keep showing the terminal screen.
     */
    suspend fun clearJobHistoryAndNotifications()

    /** Reactive mirror of the three opt-out fields, for the terminal screen's live status. */
    val optOutRecord: Flow<OptOutRecord>
}

data class OptOutRecord(val optedOutAt: Instant?, val kind: OptOutKind?, val hardDeleteConfirmed: Boolean)

class FakeJobStateStore(
    initial: Map<JobName, JobState> = emptyMap(),
    optedOutAt: Instant? = null,
    cutoffDate: LocalDate? = null,
    collectionEndedAt: Instant? = null,
) : JobStateStore {
    private val states = initial.toMutableMap()
    private val optedOutAtFlow = MutableStateFlow(optedOutAt)
    private val kindFlow = MutableStateFlow<OptOutKind?>(null)
    private val hardDeleteConfirmedFlow = MutableStateFlow(false)
    private var cutoff: LocalDate? = cutoffDate
    private val collectionEndedAtFlow = MutableStateFlow(collectionEndedAt)

    override suspend fun get(job: JobName): JobState = states[job] ?: JobState()

    override suspend fun put(job: JobName, state: JobState) {
        states[job] = state
    }

    override suspend fun optedOutAt(): Instant? = optedOutAtFlow.value

    override suspend fun setOptedOutAt(instant: Instant) {
        optedOutAtFlow.value = instant
    }

    override val hasOptedOut: Flow<Boolean> = optedOutAtFlow.map { it != null }

    override suspend fun optOutKind(): OptOutKind? = kindFlow.value

    override suspend fun setOptOutKind(kind: OptOutKind) {
        kindFlow.value = kind
    }

    override suspend fun hardDeleteConfirmed(): Boolean = hardDeleteConfirmedFlow.value

    override suspend fun setHardDeleteConfirmed(confirmed: Boolean) {
        hardDeleteConfirmedFlow.value = confirmed
    }

    override val optOutRecord: Flow<OptOutRecord> =
        combine(optedOutAtFlow, kindFlow, hardDeleteConfirmedFlow) { at, kind, confirmed -> OptOutRecord(at, kind, confirmed) }

    override suspend fun cutoffDate(): LocalDate? = cutoff

    override suspend fun setCutoffDate(date: LocalDate?) {
        cutoff = date
    }

    override suspend fun collectionEndedAt(): Instant? = collectionEndedAtFlow.value

    override suspend fun setCollectionEndedAt(instant: Instant) {
        collectionEndedAtFlow.value = instant
    }

    override val collectionEnded: Flow<Boolean> = collectionEndedAtFlow.map { it != null }

    override suspend fun clearJobHistoryAndNotifications() {
        states.clear()
    }
}
