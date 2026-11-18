package com.example.smartuse_frontend.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OptOutAckResponse(
    @SerialName("capture_status") val captureStatus: String,
    @SerialName("opted_out_at") val optedOutAt: String,
)
