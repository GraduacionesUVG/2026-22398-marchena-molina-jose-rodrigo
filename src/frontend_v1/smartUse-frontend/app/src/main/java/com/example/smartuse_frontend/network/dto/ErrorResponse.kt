package com.example.smartuse_frontend.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String? = null,
    val message: String? = null,
    /** 409 `epoch_conflict` only: every record of the request that collides with stored data. */
    val conflicts: List<EpochConflictDto>? = null,
)

@Serializable
data class EpochConflictDto(
    @SerialName("client_record_id") val clientRecordId: String? = null,
    @SerialName("start_ts") val startTs: String? = null,
)
