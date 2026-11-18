package com.example.smartuse_frontend.inference

/** USM and the broadcast stamp one screen-on at most this far apart: a use epoch opened this close before t is that screen-on. */
private const val SAME_SCREEN_ON_MS = 30_000L

/**
 * The epochs up to [t] (epoch ms) as the extractor reads them:
 * every canonical epoch up to the frontier (or [t], if the frontier is later),
 * then the tail from there to [t], rebuilt from the raw log and the service's
 * screen observations (buildTail). Gaps between tail use epochs are non-use.
 * A use epoch running at [t] ends at [t] and is `open`: its apps are not read.
 */
internal fun timelineUpTo(source: UsageSource, observations: ObservationSnapshot, t: Long): List<ModelEpoch> {
    val frontier = source.frontier()
    val tailStart = minOf(frontier ?: t, t)
    val canonical = source.firstEpochStart()
        ?.takeIf { it < tailStart }
        ?.let { first -> source.closedEpochs(first, tailStart) }
        .orEmpty()
    val tail = buildTail(
        raw = source.rawEvents(tailStart, t),
        lastRawScreenTs = source.lastScreenEventTs(t),
        rawPublishedPastT = source.hasScreenEventAtOrAfter(t),
        observations = observations,
        t = t,
    )
    val epochs = mutableListOf<SeriesEpoch>()
    epochs += canonical
    // Only the canonical part has non-use epochs: fill the tail's gaps.
    var cursor = canonical.lastOrNull()?.end ?: tail.firstOrNull()?.start ?: t
    for (use in tail) {
        if (use.start > cursor) epochs += SeriesEpoch(cursor, use.start, false, 0)
        epochs += use
        cursor = use.end
    }
    if (cursor < t) epochs += SeriesEpoch(cursor, t, false, 0)
    // A session starting exactly at t (the screen_on decision point) counts, with duration 0 (§11.11).
    // The service saw the screen come on at t, so a use epoch running into t that USM opened just
    // before (its own stamp of the same screen-on) is that session: it starts at t, not earlier.
    val onAtT = observations.observations.any { it.ts == t && it.on }
    if (onAtT) {
        val last = epochs.lastOrNull()
        if (last != null && last.isUse && last.end == t && t - last.start <= SAME_SCREEN_ON_MS) {
            epochs[epochs.lastIndex] = SeriesEpoch(last.start, t, false, 0)
            epochs += SeriesEpoch(t, t, true, 0)
        } else if (last?.let { it.isUse && it.end == t } != true) {
            epochs += SeriesEpoch(t, t, true, 0)
        }
    }
    return epochs.mapIndexed { i, e ->
        ModelEpoch(
            start = e.start / 1000.0,
            end = e.end / 1000.0,
            isUse = e.isUse,
            apps = e.appCount,
            open = i == epochs.lastIndex && e.isUse && e.end == t,
        )
    }
}

/**
 * Every captured epoch for the profile freeze, in seconds: the canonical
 * epochs, then a zero-length marker at the dangling epoch's start, where the
 * captured data ends (profilePoints reads the last epoch's start as the end).
 */
internal fun capturedEpochs(source: UsageSource): List<ModelEpoch> {
    val frontier = source.frontier() ?: return emptyList()
    val first = source.firstEpochStart()?.takeIf { it < frontier } ?: return emptyList()
    return source.closedEpochs(first, frontier).map { ModelEpoch(it.start / 1000.0, it.end / 1000.0, it.isUse, it.appCount) } +
        ModelEpoch(frontier / 1000.0, frontier / 1000.0, false, 0)
}
