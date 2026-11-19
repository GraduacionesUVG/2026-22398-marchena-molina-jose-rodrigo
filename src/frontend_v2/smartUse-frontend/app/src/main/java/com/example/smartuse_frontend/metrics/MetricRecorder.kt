package com.example.smartuse_frontend.metrics

/**
 * Aggregates one decision point's readings into a DecisionMetric: battery delta = start − end, mean of the valid
 * CURRENT_NOW readings, peak VmRSS. Called from the sampler's thread and the
 * decision point's, so every method holds one lock. A failing or invalid
 * reading is skipped.
 */
class MetricRecorder(
    private val readings: MetricReadings,
    private val elapsedMs: () -> Long,
) {
    private val lock = Any()
    private var startMs = 0L
    private var startBattery: Int? = null
    private var currentSum = 0L
    private var currentCount = 0
    private var peakRss: Long? = null

    fun start() {
        synchronized(lock) {
            startBattery = takeReading()
            startMs = elapsedMs()
        }
    }

    fun sample() {
        synchronized(lock) { takeReading() }
    }

    fun finish(kind: DecisionKind): DecisionMetric = synchronized(lock) {
        val durationMs = (elapsedMs() - startMs).coerceAtLeast(0)
        val endBattery = takeReading()
        val start = startBattery
        DecisionMetric(
            decisionPoint = kind,
            durationMs = durationMs,
            batteryDeltaPct = if (start != null && endBattery != null) start - endBattery else null,
            currentMean = if (currentCount > 0) Math.round(currentSum.toDouble() / currentCount).toInt() else null,
            peakRssKb = peakRss,
        )
    }

    /** Folds one reading into the aggregates; returns its battery %, null when unavailable. */
    private fun takeReading(): Int? {
        readOrNull { readings.currentNow() }
            ?.takeIf { it != Int.MIN_VALUE && it != 0 }
            ?.let { currentSum += it; currentCount++ }
        readOrNull { readings.rssKb() }
            ?.takeIf { it > 0 }
            ?.let { rss -> peakRss = maxOf(peakRss ?: rss, rss) }
        return readOrNull { readings.batteryPct() }?.takeIf { it in 0..100 }
    }

    private inline fun <T> readOrNull(read: () -> T?): T? = try {
        read()
    } catch (e: Exception) {
        null
    }
}
