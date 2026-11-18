package com.example.smartuse_frontend.data

import java.time.Instant

enum class OptOutKind { SOFT, HARD }

/** Where the participant stands on opting out, as the UI shows it. */
sealed interface OptOutStatus {
    object NotOptedOut : OptOutStatus
    object SoftDone : OptOutStatus
    /** [attemptFailed]: this process saw a delete attempt fail; the finish worker keeps retrying. */
    data class Hard(val deleteConfirmed: Boolean, val attemptFailed: Boolean) : OptOutStatus
}

/**
 * Pure derivation from persisted state ([optedOutAt], [kind], [hardDeleteConfirmed])
 * plus the repository's in-memory [attemptFailed] (never persisted). `kind == null`
 * always means not opted out, even if [optedOutAt] is somehow set — defensive,
 * should not occur in practice since both are always written together.
 */
fun deriveOptOutStatus(
    optedOutAt: Instant?,
    kind: OptOutKind?,
    hardDeleteConfirmed: Boolean,
    attemptFailed: Boolean,
): OptOutStatus = when {
    optedOutAt == null || kind == null -> OptOutStatus.NotOptedOut
    kind == OptOutKind.SOFT -> OptOutStatus.SoftDone
    else -> OptOutStatus.Hard(deleteConfirmed = hardDeleteConfirmed, attemptFailed = attemptFailed)
}
