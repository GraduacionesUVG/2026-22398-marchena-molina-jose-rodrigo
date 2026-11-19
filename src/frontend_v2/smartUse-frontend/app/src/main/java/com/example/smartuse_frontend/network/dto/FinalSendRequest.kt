package com.example.smartuse_frontend.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** POST /final: the end forms and the metrics, sent once. Nullable fields are omitted when null (appJson). */
@Serializable
data class FinalSendRequest(
    @SerialName("end_reason") val endReason: String,
    val forms: Map<String, FinalForm>,
    val metrics: List<MetricRecord>,
)

/** One form: its version and its answers by question id. */
@Serializable
data class FinalForm(val version: Int, val answers: JsonObject)

/** One decision point's performance record, as sent: no id, no time. An absent field means "unavailable". */
@Serializable
data class MetricRecord(
    @SerialName("decision_point") val decisionPoint: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("battery_delta_pct") val batteryDeltaPct: Int? = null,
    @SerialName("current_mean") val currentMean: Int? = null,
    @SerialName("peak_rss_kb") val peakRssKb: Long? = null,
)

/** `{"status": "stored"}` (201) or `{"status": "already_received"}` (200); null when the backend sent no readable status. */
@Serializable
data class FinalSendResponse(val status: String? = null)