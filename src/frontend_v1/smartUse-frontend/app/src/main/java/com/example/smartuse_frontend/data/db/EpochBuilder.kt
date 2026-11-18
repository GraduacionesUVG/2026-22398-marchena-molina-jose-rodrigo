package com.example.smartuse_frontend.data.db

/** One row of `raw_capture_log`, as the epoch-building stage sees it. */
internal data class RawEvent(val id: Long, val eventTs: Long, val eventType: String, val appHash: String?)

/**
 * An epoch closed by a later screen event. [closingEventTs] / [closingEventType]
 * are only carried for the deterministic epoch id; the epoch itself has no end:
 * epochs form a contiguous timeline, so one epoch ends where the next starts.
 */
internal data class BuiltEpoch(
    val startTs: Long,
    val isUse: Boolean,
    val closingEventTs: Long,
    val closingEventType: String,
    val appHashes: List<String>,
)

/**
 * [nextCursor] is what to store as `epoch_cursor`; the next run reads raw rows
 * with `id > nextCursor`. It always sits just *before* the dangling screen event
 * (see [buildEpochs]), never on it.
 */
internal data class EpochBuildResult(val epochs: List<BuiltEpoch>, val nextCursor: Long)

private const val SCREEN_INTERACTIVE = "SCREEN_INTERACTIVE"
private const val SCREEN_NON_INTERACTIVE = "SCREEN_NON_INTERACTIVE"
private const val ACTIVITY_RESUMED = "ACTIVITY_RESUMED"

/**
 * Pairs consecutive screen events from [rows] (raw rows with `id > cursor`, in id
 * order) into closed epochs. The last screen event has no successor yet, so it
 * opens an epoch that cannot close and stays dangling for the next run.
 *
 * That event also *closed* the final epoch built here, but it is the opening
 * boundary of the next one, so the next run must read it again: the cursor is
 * parked one id before it. Leaving the cursor on it would make `id > cursor`
 * skip it and silently lose the epoch that starts there (the epoch before it
 * would then appear to stretch over the gap, since an epoch's end is the next
 * epoch's start). With no epoch built the cursor is unchanged, which already
 * leaves a lone dangling event in place.
 */
internal fun buildEpochs(rows: List<RawEvent>, cursor: Long): EpochBuildResult {
    val screenEvents = rows.filter { it.eventType == SCREEN_INTERACTIVE || it.eventType == SCREEN_NON_INTERACTIVE }
    val interactionEvents = rows.filter { it.eventType == ACTIVITY_RESUMED }

    val epochs = buildList {
        for (i in 0 until screenEvents.size - 1) {
            val start = screenEvents[i]
            val end = screenEvents[i + 1]
            val isUse = start.eventType == SCREEN_INTERACTIVE

            val appHashes = if (isUse) {
                interactionEvents
                    .filter { it.eventTs >= start.eventTs && it.eventTs < end.eventTs }
                    .mapNotNull { it.appHash }
                    .distinct()
            } else {
                emptyList()
            }

            add(BuiltEpoch(start.eventTs, isUse, end.eventTs, end.eventType, appHashes))
        }
    }
    val nextCursor = if (epochs.isEmpty()) cursor else screenEvents.last().id - 1
    return EpochBuildResult(epochs, nextCursor)
}
