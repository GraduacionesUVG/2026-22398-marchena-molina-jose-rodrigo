package com.example.smartuse_frontend.inference

import com.example.smartuse_frontend.data.db.RawEvent

/** What the timeline reads from canonical.db and the raw log (SqliteUsageSource on the device). */
internal interface UsageSource {
    /** The canonical frontier: where the dangling epoch starts (first raw screen event after the epoch cursor); null without one. */
    fun frontier(): Long?

    /** The last raw screen event at or before [atOrBefore], or null. */
    fun lastScreenEventTs(atOrBefore: Long): Long?

    /** Whether a raw screen event exists at or after [ts]. */
    fun hasScreenEventAtOrAfter(ts: Long): Boolean

    /** Raw screen and ACTIVITY_RESUMED rows with `from ≤ ts < to`, by time. */
    fun rawEvents(from: Long, to: Long): List<RawEvent>

    /**
     * canonical.db epochs that start before [end] and overlap `[from, end)`.
     * Each ends where the next starts, the last at [end]; callers pass an
     * [end] that is a canonical epoch start or the frontier.
     */
    fun closedEpochs(from: Long, end: Long): List<SeriesEpoch>

    fun firstEpochStart(): Long?
}
