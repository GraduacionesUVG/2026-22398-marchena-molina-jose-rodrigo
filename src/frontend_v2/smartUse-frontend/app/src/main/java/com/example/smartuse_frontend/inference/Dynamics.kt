package com.example.smartuse_frontend.inference

import kotlin.math.ln
import kotlin.math.sign

/** A dynamics scale (§7.7): consecutive bins ending at t. */
private enum class Scale(val label: String, val bins: Int, val binS: Double) {
    M15("15m", 3, 300.0),
    H1("1h", 4, 900.0),
    H24("24h", 24, 3600.0),
}

private val SIGNALS = listOf("n", "min")
private val SUFFIXES = listOf("slope", "accel", "rel_change", "com", "peak", "persist")
private const val EPS = 0.01

/** The 42 F-only features, in the model file's order (§8). */
val DYNAMICS_FEATURES: List<String> =
    Scale.entries.flatMap { scale -> SIGNALS.flatMap { signal -> SUFFIXES.map { "${signal}_${scale.label}_$it" } } } +
        SIGNALS.flatMap { listOf("${it}_var_15m_1h", "${it}_var_1h_24h", "${it}_level_15m_24h") }

/**
 * §7.7 on [record] (sessions started at or before t, ends clipped to t).
 * Per bin: `n` = sessions started in it (so not one starting at t), `min` =
 * minutes of screen-on in it, the current session counting up to t.
 */
fun dynamicsFeatures(record: List<Session>, t: Double): List<Double> {
    val series = Scale.entries.associateWith { scale ->
        val start = t - scale.bins * scale.binS
        val bins = (0 until scale.bins).map { i -> start + i * scale.binS to start + (i + 1) * scale.binS }
        mapOf(
            "n" to bins.map { (a, b) -> record.count { it.start >= a && it.start < b }.toDouble() },
            "min" to bins.map { (a, b) -> record.sumOf { maxOf(0.0, minOf(it.end, b) - maxOf(it.start, a)) } / 60.0 },
        )
    }
    val shape = Scale.entries.flatMap { scale ->
        SIGNALS.flatMap { signal ->
            val x = series.getValue(scale).getValue(signal)
            listOf(slope(x), accel(x), relChange(x), centreOfMass(x), peak(x), persist(x))
        }
    }
    val cross = SIGNALS.flatMap { signal ->
        val rates = Scale.entries.associateWith { scale -> series.getValue(scale).getValue(signal).map { it / (scale.binS / 60.0) } }
        val sd = rates.mapValues { populationSd(it.value) }
        val mean = rates.mapValues { it.value.average() }
        listOf(
            ln((sd.getValue(Scale.M15) + EPS) / (sd.getValue(Scale.H1) + EPS)),
            ln((sd.getValue(Scale.H1) + EPS) / (sd.getValue(Scale.H24) + EPS)),
            ln((mean.getValue(Scale.M15) + EPS) / (mean.getValue(Scale.H24) + EPS)),
        )
    }
    return shape + cross
}

/** Least-squares slope of x against 0..B−1 (population cov / var); 0 for fewer than 2 bins. */
private fun slope(x: List<Double>): Double {
    if (x.size < 2) return 0.0
    val idx = x.indices.map { it.toDouble() }
    val mi = idx.average()
    val mx = x.average()
    val cov = idx.indices.sumOf { (idx[it] - mi) * (x[it] - mx) }
    val varI = idx.sumOf { (it - mi) * (it - mi) }
    return cov / varI
}

private fun accel(x: List<Double>): Double {
    val half = (x.size + 1) / 2
    return slope(x.takeLast(half)) - slope(x.take(half))
}

private fun relChange(x: List<Double>): Double {
    val before = x.dropLast(1).average()
    return (x.last() - before) / (before + 1)
}

private fun centreOfMass(x: List<Double>): Double {
    val total = x.sum()
    if (total == 0.0) return 0.5
    return x.indices.sumOf { it * x[it] } / total / (x.size - 1)
}

private fun peak(x: List<Double>): Double {
    if (x.sum() == 0.0) return 0.0
    return x.max() / x.average()
}

/** How many consecutive differences from the end share the last one's sign, with that sign; 0 if the last is 0. */
private fun persist(x: List<Double>): Double {
    val d = x.zipWithNext { a, b -> b - a }
    val lastSign = sign(d.last())
    if (lastSign == 0.0) return 0.0
    val run = d.asReversed().takeWhile { sign(it) == lastSign }.size
    return lastSign * run
}
