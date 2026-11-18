package com.example.smartuse_frontend.inference

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.ln1p

private const val DAY_S = 86_400.0

/**
 * One model's frozen profile (models/extractor_features.md §6.3). Field names
 * on the wire match the researcher's fixture JSON. Usage data: lives in
 * canonical.db, never sent.
 */
@Serializable
data class Profile(
    val pf: Double,
    val ps: Double,
    @SerialName("f_q") val fQ: List<Double>,
    @SerialName("s_q") val sQ: List<Double>,
    @SerialName("f_median") val fMedian: Double,
    @SerialName("s_median") val sMedian: Double,
    @SerialName("freq_day") val freqDay: Double,
    @SerialName("use_day_min") val useDayMin: Double,
    @SerialName("logdur_median") val logdurMedian: Double,
    @SerialName("nonuse_day_min") val nonuseDayMin: Double,
    @SerialName("lognonuse_median") val lognonuseMedian: Double,
    @SerialName("sessions_by_hour") val sessionsByHour: List<Double>,
    /** The profile days, ISO local dates. */
    @SerialName("profile_days") val profileDays: List<String>,
    @SerialName("offset_s") val offsetS: Double,
    @SerialName("n_profile_points") val nProfilePoints: Int,
)

/** numpy's default quantile (linear, Hyndman-Fan type 7) of an ascending list. */
fun quantile7(sorted: List<Double>, q: Double): Double {
    val pos = q * (sorted.size - 1)
    val lo = floor(pos).toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo])
}

fun median(values: List<Double>): Double = quantile7(values.sorted(), 0.5)

/** Labels-window offset of each model's profile (§6). */
fun labelOffsetMin(pattern: Pattern): Int = when (pattern) {
    Pattern.F -> 0
    Pattern.S -> 30
}

/**
 * §6.1: the first 3 local days with at least one usable point. Both profiles
 * use the same days (§6); they are taken from F's points (b = 0).
 */
fun profileDays(points: List<ProfilePoint>, offsetS: Double): List<Long> =
    points.filter { it.usable }.map { localDay(it.t, offsetS) }.distinct().sorted().take(ModelRules.PROFILE_DAYS)

/** When the profile may be frozen: one window (60 min) after the third profile day ends (§6.1). */
fun profileReadyAt(days: List<Long>, offsetS: Double): Double =
    (days.last() + 1) * DAY_S - offsetS + ModelRules.W_MIN * 60.0

/**
 * §6.3 from the whole record; null until there are 3 profile days. `pf`/`ps`
 * are the person's own p80: with 3 days the deployment profile is the
 * person's own (§6.4, "E1b"; the fixtures confirm it), so the population
 * prior in ModelRules.PRIOR is not blended in.
 */
fun buildProfile(pattern: Pattern, epochs: List<ModelEpoch>, sessions: List<Session>, offsetS: Double): Profile? {
    val days = profileDays(profilePoints(epochs, sessions, offsetS, labelOffsetMin(Pattern.F)), offsetS)
    if (days.size < ModelRules.PROFILE_DAYS) return null
    return buildProfile(pattern, epochs, sessions, offsetS, days) { it.usable }
}

/**
 * §6.3 on the given [days] from the points [keep] accepts (the forced freeze
 * passes fewer days or looser points); every per-day median is over [days].
 * Null when the pattern has no kept point on them.
 */
fun buildProfile(
    pattern: Pattern,
    epochs: List<ModelEpoch>,
    sessions: List<Session>,
    offsetS: Double,
    days: List<Long>,
    keep: (ProfilePoint) -> Boolean,
): Profile? {
    val points = profilePoints(epochs, sessions, offsetS, labelOffsetMin(pattern))
        .filter { keep(it) && localDay(it.t, offsetS) in days }
    if (points.isEmpty()) return null
    val f = points.map { it.metrics.f }.sorted()
    val s = points.map { it.metrics.s }.sorted()
    val grid = (1..19).map { it * 0.05 }
    val daily = days.map { dayRecord(sessions, it, offsetS) }
    return Profile(
        pf = quantile7(f, ModelRules.P),
        ps = quantile7(s, ModelRules.P),
        fQ = grid.map { quantile7(f, it) },
        sQ = grid.map { quantile7(s, it) },
        fMedian = quantile7(f, 0.5),
        sMedian = quantile7(s, 0.5),
        freqDay = median(daily.map { it.freq }),
        useDayMin = median(daily.map { it.useMin }),
        logdurMedian = median(daily.map { it.logdurMedian }),
        nonuseDayMin = median(daily.map { it.nonuseMin }),
        lognonuseMedian = median(daily.map { it.lognonuseMedian }),
        sessionsByHour = (0 until 24).map { h -> median(daily.map { it.byHour[h] }) },
        profileDays = days.map { LocalDate.ofEpochDay(it).toString() },
        offsetS = offsetS,
        nProfilePoints = points.size,
    )
}

/** The days of one forced tier and the points it keeps. */
class ForcedDays(val days: List<Long>, val keep: (ProfilePoint) -> Boolean)

/**
 * The forced freeze's tiers, closest to §6.1 first:
 * usable points; points neither masked nor censored (the day fails only the
 * 6-hour rule); any point. Each tier takes up to 3 of its earliest days from
 * F's points (b = 0), like [profileDays], leaving out a day still in progress
 * (not yet [profileReadyAt] at [dataEnd]) unless it is the tier's only one;
 * tiers with no point are left out.
 */
fun forcedTiers(points: List<ProfilePoint>, offsetS: Double, dataEnd: Double): List<ForcedDays> =
    listOf<(ProfilePoint) -> Boolean>({ it.usable }, { !it.masked && !it.censored }, { true })
        .map { keep ->
            val all = points.filter(keep).map { localDay(it.t, offsetS) }.distinct().sorted()
            val done = all.filter { dataEnd >= profileReadyAt(listOf(it), offsetS) }
            ForcedDays((done.ifEmpty { all }).take(ModelRules.PROFILE_DAYS), keep)
        }
        .filter { it.days.isNotEmpty() }

private class DayRecord(
    val freq: Double,
    val useMin: Double,
    val logdurMedian: Double,
    val nonuseMin: Double,
    val lognonuseMedian: Double,
    val byHour: List<Double>,
)

/** One local day's session record (§6.3, second table): sessions clipped to the day. */
private fun dayRecord(sessions: List<Session>, day: Long, offsetS: Double): DayRecord {
    val dayStart = day * DAY_S - offsetS
    val dayEnd = dayStart + DAY_S
    val clipped = sessions.filter { it.start < dayEnd && it.end > dayStart }
        .map { maxOf(it.start, dayStart) to minOf(it.end, dayEnd) }
    val durations = clipped.map { it.second - it.first }
    val gaps = clipped.zipWithNext { a, b -> b.first - a.second }
    val byHour = MutableList(24) { 0.0 }
    // Started in each hour: a session carried over from the day before (clipped at midnight) started earlier.
    sessions.filter { it.start >= dayStart && it.start < dayEnd }
        .forEach { byHour[floor((it.start - dayStart) / 3600.0).toInt()] += 1.0 }
    return DayRecord(
        freq = clipped.size.toDouble(),
        useMin = durations.sum() / 60.0,
        logdurMedian = if (durations.size >= 2) median(durations.map { ln1p(it) }) else 0.0,
        nonuseMin = gaps.sum() / 60.0,
        lognonuseMedian = if (gaps.size >= 2) median(gaps.map { ln1p(it) }) else 0.0,
        byHour = byHour,
    )
}
