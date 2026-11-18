package com.example.smartuse_frontend.jobs

enum class GateResult {
    /** Run the job. */
    OPEN,

    /** Setup not finished: succeed quietly, keep the schedule. */
    NO_SESSION,

    /** Day 21 or the cutoff has passed: succeed, and run the ending (which cancels everything). */
    WINDOW_CLOSED,

    /** The participant opted out: succeed and cancel everything. */
    OPTED_OUT,

    /** The ending already ran: succeed and cancel everything. */
    COLLECTION_ENDED,
}

/**
 * Evaluated at the start of every job run. The terminal states come first,
 * so an ended or opted-out install never runs again even with its session
 * still stored.
 */
class JobGate(
    private val hasSession: suspend () -> Boolean,
    private val isWindowOpen: suspend () -> Boolean,
    private val isOptedOut: suspend () -> Boolean,
    private val isCollectionEnded: suspend () -> Boolean,
) {
    suspend fun check(): GateResult = when {
        isOptedOut() -> GateResult.OPTED_OUT
        isCollectionEnded() -> GateResult.COLLECTION_ENDED
        !hasSession() -> GateResult.NO_SESSION
        !isWindowOpen() -> GateResult.WINDOW_CLOSED
        else -> GateResult.OPEN
    }

    /** The study is running. The one condition every service start checks: launch, boot, cycle, sticky restart. */
    suspend fun isStudyLive(): Boolean = check() == GateResult.OPEN
}
