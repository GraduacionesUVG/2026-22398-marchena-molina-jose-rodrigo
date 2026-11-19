package com.example.smartuse_frontend.inference

import com.example.smartuse_frontend.data.db.RawEvent

/** An epoch as the timeline sees it: `[start, end)` in epoch ms, `end` already cut at t where needed. */
data class SeriesEpoch(val start: Long, val end: Long, val isUse: Boolean, val appCount: Int)

private const val SCREEN_ON = "SCREEN_INTERACTIVE"
private const val SCREEN_OFF = "SCREEN_NON_INTERACTIVE"
private const val ACTIVITY = "ACTIVITY_RESUMED"

/**
 * The use epochs of the tail `[S, t)`, built in memory and never stored. Only use epochs come out (timelineUpTo fills the
 * gaps with non-use); the last one is cut at [t].
 *
 * @param raw raw rows with `S ≤ ts < t` (screen events and ACTIVITY_RESUMED).
 * @param lastRawScreenTs the last raw screen event at or before [t], null if none.
 * @param rawPublishedPastT a raw screen event exists at or after [t]: USM covers
 *   the whole tail and the service's observations are not needed.
 *
 * Nothing observed is non-use: after the last USM screen event, its state
 * carries on only while the service was observing; otherwise use ends at the
 * last raw event before the service took over (or at that screen event).
 * A transition to the state already current is dropped, so the same screen-on
 * seen by USM and by the service is one pickup.
 */
internal fun buildTail(
    raw: List<RawEvent>,
    lastRawScreenTs: Long?,
    rawPublishedPastT: Boolean,
    observations: ObservationSnapshot,
    t: Long,
): List<SeriesEpoch> {
    val sorted = raw.sortedWith(compareBy({ it.eventTs }, { it.id }))
    val transitions = sorted
        .filter { it.eventType == SCREEN_ON || it.eventType == SCREEN_OFF }
        .map { ScreenObservation(it.eventTs, it.eventType == SCREEN_ON) }
        .toMutableList()

    if (!rawPublishedPastT) {
        val since = observations.observingSince
        if (lastRawScreenTs != null && (since == null || since > lastRawScreenTs)) {
            val unwatchedUntil = since ?: t
            val lastEvidence = sorted.filter { it.eventTs < unwatchedUntil }.maxOfOrNull { it.eventTs } ?: lastRawScreenTs
            transitions += ScreenObservation(maxOf(lastEvidence, lastRawScreenTs), false)
        }
        transitions += observations.observations.filter { (lastRawScreenTs == null || it.ts > lastRawScreenTs) && it.ts < t }
    }

    val activity = sorted.filter { it.eventType == ACTIVITY }
    val epochs = mutableListOf<SeriesEpoch>()
    var openedAt: Long? = null
    fun close(end: Long) {
        val start = openedAt ?: return
        val apps = activity.filter { it.eventTs >= start && it.eventTs < end }.mapNotNull { it.appHash }.distinct().size
        epochs += SeriesEpoch(start, end, true, apps)
        openedAt = null
    }
    // sortedBy is stable: at equal times raw comes before the forced end, which comes before the service's.
    for (transition in transitions.sortedBy { it.ts }) {
        if (transition.ts >= t) break
        if (transition.on && openedAt == null) openedAt = transition.ts
        else if (!transition.on && openedAt != null) close(transition.ts)
    }
    close(t)
    return epochs
}
