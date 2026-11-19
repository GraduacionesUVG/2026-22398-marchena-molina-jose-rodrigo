package com.example.smartuse_frontend.metrics

/** Which decision point a record measured. `wire` is the value stored and sent. */
enum class DecisionKind(val wire: String) {
    /** The unlock that opens a session. */
    SCREEN_ON("screen_on"),

    /** Every 10 min of use after it. */
    ACTIVE_USE("active_use"),
}

/**
 * One decision point's performance record: capture, features, both models and
 * the rules. NULL = the device could not provide the value. Nothing here
 * identifies the participant, the time, the apps, the scores or the decision.
 */
data class DecisionMetric(
    val decisionPoint: DecisionKind,
    val durationMs: Long,
    val batteryDeltaPct: Int?,
    val currentMean: Int?,
    val peakRssKb: Long?,
) {
    fun describe(): String =
        "decision_point=${decisionPoint.wire} duration_ms=$durationMs battery_delta_pct=$batteryDeltaPct " +
            "current_mean=$currentMean peak_rss_kb=$peakRssKb"
}
