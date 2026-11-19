package com.example.smartuse_frontend.inference

import kotlin.math.floor
import kotlin.math.ln1p
import kotlin.math.sqrt

/** The 36 features both models share, in the order of the model files (models/extractor_features.md §8). */
val S_FEATURES: List<String> = listOf(
    "cur_logdur", "since_last_logmin", "no_previous", "n_15", "f_15",
    "logdur_median_15", "logdur_mean_15", "few2_15", "logdur_sd_15", "burst_15", "few3_15",
    "apps_mean_15", "apps_max_15", "noapps_15", "freq_day", "use_day_logmin",
    "logdur_median_day", "nonuse_day_logmin", "lognonuse_median_day",
    "since_first_logmin", "burst_day", "max_f60_day", "max_s60_day", "positive_hours_day",
    "prof_pf", "prof_ps", "prof_f_median", "prof_s_median", "prof_freq_day",
    "prof_use_day_logmin", "prof_logdur_median", "prof_nonuse_day_logmin",
    "prof_lognonuse_median", "f60_rank", "s60_rank", "sessions_vs_hour_typical",
)

/** F: the 36 with F's profile, then the 42 dynamics features (§7.7). */
val F_FEATURES: List<String> = S_FEATURES + DYNAMICS_FEATURES

private const val DAY_S = 86_400.0
private const val HOUR_S = 3_600.0
private const val WINDOW_15_S = 900.0

/**
 * The model's input at [t] (§7-8), as float32 in the model file's order.
 * [sessions] is the whole record: only what started at or before [t] is
 * used, ends clipped to [t] (§7.1). The current session's apps are never read.
 */
fun extract(pattern: Pattern, sessions: List<Session>, t: Double, profile: Profile, offsetS: Double): FloatArray {
    val record = sessions.filter { it.start <= t }.map { if (it.end > t) it.copy(end = t) else it }
    val currentIndex = record.indices.lastOrNull()?.takeIf { sessions[it].end >= t }
    val shared = sharedFeatures(record, currentIndex, t, profile, offsetS)
    val values = if (pattern == Pattern.F) shared + dynamicsFeatures(record, t) else shared
    return FloatArray(values.size) { values[it].toFloat() }
}

private fun sharedFeatures(record: List<Session>, currentIndex: Int?, t: Double, profile: Profile, offsetS: Double): List<Double> {
    val current = currentIndex?.let { record[it] }
    val closed = if (currentIndex != null) record.subList(0, currentIndex) else record
    val previous = closed.lastOrNull()

    // §7.2 current state.
    val curLogdur = current?.let { ln1p(t - it.start) } ?: 0.0
    val sinceLast = when {
        previous == null -> 0.0
        current != null -> ln1p((current.start - previous.end) / 60.0)
        else -> ln1p((t - previous.end) / 60.0)
    }
    val noPrevious = if (previous == null) 1.0 else 0.0

    // §7.3 the last 15 min: sessions whose clipped end is after t − 900, starts clipped to the window.
    val w15Start = t - WINDOW_15_S
    val in15 = record.withIndex().filter { it.value.end > w15Start }
    val spans15 = in15.map { maxOf(it.value.start, w15Start) to it.value.end }
    val n15 = spans15.size
    // F of the half-open window (§6.2): unlike n_15, it leaves out a session starting at t (fixtures).
    val f15 = windowMetrics(record, w15Start, WINDOW_15_S).f
    val logdur15 = spans15.map { ln1p(it.second - it.first) }
    val gaps15 = spans15.zipWithNext { a, b -> b.first - a.second }
    val apps15 = in15.filter { it.index != currentIndex }.map { it.value.apps.toDouble() }

    // §7.4 the local day up to t: sessions that started strictly before t, clipped to [midnight, t).
    val midnight = floor((t + offsetS) / DAY_S) * DAY_S - offsetS
    val day = record.filter { it.start < t && it.end > midnight }.map { maxOf(it.start, midnight) to it.end }
    val dayDurations = day.map { it.second - it.first }
    val dayGaps = day.zipWithNext { a, b -> b.first - a.second }
    val beforeT = record.filter { it.start < t }
    var hour = midnight
    var maxF = 0.0
    var maxS = 0.0
    var positiveHours = 0
    while (hour + HOUR_S <= t) {
        val m = windowMetrics(beforeT, hour, HOUR_S)
        maxF = maxOf(maxF, m.f)
        maxS = maxOf(maxS, m.s)
        if (m.f > profile.pf || m.s > profile.ps) positiveHours++
        hour += HOUR_S
    }

    // §7.5 the last hour against the frozen profile.
    val last60 = windowMetrics(record, t - HOUR_S, HOUR_S)
    val localHour = floor(((t + offsetS) % DAY_S + DAY_S) % DAY_S / HOUR_S).toInt()
    val startedLastHour = record.count { it.start >= t - HOUR_S && it.start < t }

    return listOf(
        curLogdur,
        sinceLast,
        noPrevious,
        n15.toDouble(),
        f15,
        if (n15 >= 2) median(logdur15) else 0.0,
        if (n15 >= 2) logdur15.average() else 0.0,
        if (n15 < 2) 1.0 else 0.0,
        if (n15 >= 3) populationSd(logdur15) else 0.0,
        if (gaps15.size >= 3) burstiness(gaps15) else 0.0,
        if (gaps15.size < 3) 1.0 else 0.0,
        if (apps15.isNotEmpty()) apps15.average() else 0.0,
        apps15.maxOrNull() ?: 0.0,
        if (apps15.isEmpty()) 1.0 else 0.0,
        day.size.toDouble(),
        ln1p(dayDurations.sum() / 60.0),
        if (dayDurations.size >= 2) median(dayDurations.map { ln1p(it) }) else 0.0,
        ln1p(dayGaps.sum() / 60.0),
        if (dayGaps.size >= 2) median(dayGaps.map { ln1p(it) }) else 0.0,
        day.firstOrNull()?.let { ln1p((t - it.first) / 60.0) } ?: 0.0,
        if (dayGaps.size >= 3) burstiness(dayGaps) else 0.0,
        maxF,
        maxS,
        positiveHours.toDouble(),
        profile.pf,
        profile.ps,
        profile.fMedian,
        profile.sMedian,
        profile.freqDay,
        ln1p(profile.useDayMin),
        profile.logdurMedian,
        ln1p(profile.nonuseDayMin),
        profile.lognonuseMedian,
        rank(last60.f, profile.fQ),
        rank(last60.s, profile.sQ),
        startedLastHour - profile.sessionsByHour[localHour],
    )
}

/** §7.6: Kim and Jo (2016) burstiness of [intervals]; 0 when their mean is 0. */
fun burstiness(intervals: List<Double>): Double {
    val mean = intervals.average()
    if (mean == 0.0) return 0.0
    val r = populationSd(intervals) / mean
    val m = intervals.size.toDouble()
    return (sqrt(m + 1) * r - sqrt(m - 1)) / ((sqrt(m + 1) - 2) * r + sqrt(m - 1))
}

fun populationSd(values: List<Double>): Double {
    val mean = values.average()
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
}

/**
 * Position of [x] among the profile's quantiles 0.05 … 0.95, as `numpy.interp`
 * (clamped outside the range; on repeated quantiles, the last one ≤ x wins).
 */
private fun rank(x: Double, quantiles: List<Double>): Double {
    val levels = quantiles.indices.map { (it + 1) * 0.05 }
    val last = quantiles.lastIndex
    if (x < quantiles[0]) return levels[0]
    if (x > quantiles[last]) return levels[last]
    val j = quantiles.indexOfLast { it <= x }
    if (j == last || quantiles[j] == x) return levels[j]
    return levels[j] + (x - quantiles[j]) * (levels[j + 1] - levels[j]) / (quantiles[j + 1] - quantiles[j])
}
