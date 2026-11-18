package com.example.smartuse_frontend.inference

import kotlin.math.floor
import kotlin.math.ln

private const val DAY_S = 86_400.0
private const val HOUR_S = 3_600.0

/** Metrics of one window `[a, a + w)` (models/extractor_features.md §6.2): sessions clipped to it. */
data class WindowMetrics(val n: Int, val span: Double, val longest: Double, val f: Double, val s: Double)

/** n = sessions overlapping; F = n · span / w when n ≥ 2; S = ln(max(longest clipped, 1 s)), 0 without sessions. */
fun windowMetrics(sessions: List<Session>, a: Double, w: Double): WindowMetrics {
    val b = a + w
    val clipped = sessions.filter { it.start < b && it.end > a }.map { maxOf(it.start, a) to minOf(it.end, b) }
    if (clipped.isEmpty()) return WindowMetrics(0, 0.0, 0.0, 0.0, 0.0)
    val n = clipped.size
    val span = clipped.last().second - clipped.first().first
    val longest = clipped.maxOf { it.second - it.first }
    return WindowMetrics(
        n = n,
        span = span,
        longest = longest,
        f = if (n >= 2) n * span / w else 0.0,
        s = ln(maxOf(longest, 1.0)),
    )
}

/** A decision point (§4): [isUnlock] for the one at a session's start. */
data class PointT(val t: Double, val isUnlock: Boolean)

/** §4: each session's start, then every k minutes while before its end and before start + 3 h. */
fun decisionPoints(sessions: List<Session>): List<PointT> = sessions.flatMap { s ->
    val step = ModelRules.K_MIN * 60.0
    val limit = minOf(s.end, s.start + ModelRules.T_MAX_H * HOUR_S)
    buildList {
        add(PointT(s.start, true))
        var i = 1
        while (s.start + i * step < limit) {
            add(PointT(s.start + i * step, false))
            i++
        }
    }
}

/** The local date of [t] as an epoch-day number. */
fun localDay(t: Double, offsetS: Double): Long = floor((t + offsetS) / DAY_S).toLong()

/** §5: local days with epoch starts in at least 6 distinct clock hours. */
fun sufficientDays(epochs: List<ModelEpoch>, offsetS: Double): Set<Long> =
    mergeEpochs(epochs)
        .map { floor((it.start + offsetS) / HOUR_S).toLong() }
        .distinct()
        .groupingBy { Math.floorDiv(it, 24L) }
        .eachCount()
        .filterValues { it >= ModelRules.DAY_MIN_HOURS }
        .keys

/**
 * One decision point with its profile window `[t − offset, t − offset + 60 min)`
 * (§6.1). [masked]: the window overlaps a no_close session. [censored]: it
 * starts before the data, ends after it (the start of the last, open epoch), or
 * overlaps missing data (a no-use epoch over 10 h). Both definitions were
 * pinned against the researcher's fixtures (points_b0/b30.csv), where §6.1
 * lists the three conditions without naming the columns.
 */
data class ProfilePoint(
    val t: Double,
    val isUnlock: Boolean,
    val metrics: WindowMetrics,
    val masked: Boolean,
    val censored: Boolean,
    val dayOk: Boolean,
) {
    val usable: Boolean get() = !masked && !censored && dayOk
}

/** Every decision point of the data with its profile window; [labelOffsetMin] is 0 for F and 30 for S. */
fun profilePoints(epochs: List<ModelEpoch>, sessions: List<Session>, offsetS: Double, labelOffsetMin: Int): List<ProfilePoint> {
    if (epochs.isEmpty()) return emptyList()
    val w = ModelRules.W_MIN * 60.0
    // The last epoch is the open one (on the device, the dangling epoch): the data ends where it starts.
    val dataStart = epochs.first().start
    val dataEnd = epochs.last().start
    val days = sufficientDays(epochs, offsetS)
    val noClose = sessions.filter { it.noClose }.map { it.start to it.end }
    val missing = mergeEpochs(epochs)
        .filter { !it.isUse && it.end - it.start > ModelRules.MISSING_GAP_S }
        .map { it.start to it.end }
    fun overlaps(spans: List<Pair<Double, Double>>, a: Double, b: Double) = spans.any { (s, e) -> s < b && e > a }
    return decisionPoints(sessions).map { p ->
        val a = p.t - labelOffsetMin * 60.0
        val b = a + w
        ProfilePoint(
            t = p.t,
            isUnlock = p.isUnlock,
            metrics = windowMetrics(sessions, a, w),
            masked = overlaps(noClose, a, b),
            censored = a < dataStart || b > dataEnd || overlaps(missing, a, b),
            dayOk = localDay(p.t, offsetS) in days,
        )
    }
}
