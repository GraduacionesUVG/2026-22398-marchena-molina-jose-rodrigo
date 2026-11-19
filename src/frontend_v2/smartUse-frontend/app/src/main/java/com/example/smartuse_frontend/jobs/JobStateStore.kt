package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.inference.FiringState
import com.example.smartuse_frontend.study.EndMarks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate

interface JobStateStore {
    suspend fun get(job: JobName): JobState
    suspend fun put(job: JobName, state: JobState)

    /** Read by JobGate; written by the opt-out. */
    suspend fun optedOutAt(): Instant?
    suspend fun setOptedOutAt(instant: Instant)


    /** The study-wide cutoff as last fetched from GET /cutoff-deployment-group; null = none published. */
    suspend fun cutoffDate(): LocalDate?
    suspend fun setCutoffDate(date: LocalDate?)

    /** Set once, by StudyEnder, when collection ends (day 21 or the cutoff). */
    suspend fun collectionEndedAt(): Instant?
    suspend fun setCollectionEndedAt(instant: Instant)


    /** Set once, by FormsSubmitter (or EndingCleanup after a crash), when the end forms are frozen. */
    suspend fun formsCompletedAt(): Instant?
    suspend fun setFormsCompletedAt(instant: Instant)

    /** Set once, by FinalSendRunner, when POST /final answered 2xx. */
    suspend fun finalSentAt(): Instant?
    suspend fun setFinalSentAt(instant: Instant)

    /** Set once, by EndingCleanup, after the steps that follow the confirmed final send. */
    suspend fun endCleanupDoneAt(): Instant?
    suspend fun setEndCleanupDoneAt(instant: Instant)

    /** Every ending mark at once. */
    suspend fun readEndMarks(): EndMarks

    /** Reactive [readEndMarks], for the root route and the service mode. */
    val endMarks: Flow<EndMarks>

    /** The execution rules' control state: never sent, never analysed. */
    suspend fun firingState(): FiringState
    suspend fun setFiringState(state: FiringState)
}

class FakeJobStateStore(
    initial: Map<JobName, JobState> = emptyMap(),
    optedOutAt: Instant? = null,
    cutoffDate: LocalDate? = null,
    collectionEndedAt: Instant? = null,
    formsCompletedAt: Instant? = null,
    finalSentAt: Instant? = null,
) : JobStateStore {
    private val states = initial.toMutableMap()
    private val marks = MutableStateFlow(
        EndMarks(
            collectionEndedAt = collectionEndedAt,
            optedOutAt = optedOutAt,
            formsCompletedAt = formsCompletedAt,
            finalSentAt = finalSentAt,
        ),
    )
    private var cutoff: LocalDate? = cutoffDate
    private var cleanupDone: Instant? = null
    private var firing = FiringState()

    override suspend fun get(job: JobName): JobState = states[job] ?: JobState()

    override suspend fun put(job: JobName, state: JobState) {
        states[job] = state
    }

    override suspend fun optedOutAt(): Instant? = marks.value.optedOutAt

    override suspend fun setOptedOutAt(instant: Instant) {
        marks.update { it.copy(optedOutAt = instant) }
    }


    override suspend fun cutoffDate(): LocalDate? = cutoff

    override suspend fun setCutoffDate(date: LocalDate?) {
        cutoff = date
    }

    override suspend fun collectionEndedAt(): Instant? = marks.value.collectionEndedAt

    override suspend fun setCollectionEndedAt(instant: Instant) {
        marks.update { it.copy(collectionEndedAt = instant) }
    }


    override suspend fun formsCompletedAt(): Instant? = marks.value.formsCompletedAt

    override suspend fun setFormsCompletedAt(instant: Instant) {
        marks.update { it.copy(formsCompletedAt = instant) }
    }

    override suspend fun finalSentAt(): Instant? = marks.value.finalSentAt

    override suspend fun setFinalSentAt(instant: Instant) {
        marks.update { it.copy(finalSentAt = instant) }
    }

    override suspend fun endCleanupDoneAt(): Instant? = cleanupDone

    override suspend fun setEndCleanupDoneAt(instant: Instant) {
        cleanupDone = instant
    }

    override suspend fun readEndMarks(): EndMarks = marks.value

    override val endMarks: Flow<EndMarks> = marks

    override suspend fun firingState(): FiringState = firing

    override suspend fun setFiringState(state: FiringState) {
        firing = state
    }
}