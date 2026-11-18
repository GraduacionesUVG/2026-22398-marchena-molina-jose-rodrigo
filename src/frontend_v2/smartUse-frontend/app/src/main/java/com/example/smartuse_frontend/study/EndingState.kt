package com.example.smartuse_frontend.study

import java.time.Instant

/** Where the study's ending stands. It only moves forward. */
enum class EndingState { LIVE, ENDED, FORMS_DONE, SENT }

/** Why the study ended; [wire] is `end_reason` in POST /final. */
enum class EndReason(val wire: String) { COMPLETED("completed"), OPT_OUT("opt_out") }

/** The ending's marks as stored in JobStateStore; the state and the reason derive from them. */
data class EndMarks(
    val collectionEndedAt: Instant? = null,
    val optedOutAt: Instant? = null,
    val formsCompletedAt: Instant? = null,
    val finalSentAt: Instant? = null,
) {
    val state: EndingState
        get() = when {
            finalSentAt != null -> EndingState.SENT
            formsCompletedAt != null -> EndingState.FORMS_DONE
            collectionEndedAt != null || optedOutAt != null -> EndingState.ENDED
            else -> EndingState.LIVE
        }

    val reason: EndReason
        get() = if (optedOutAt != null) EndReason.OPT_OUT else EndReason.COMPLETED
}