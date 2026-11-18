package com.example.smartuse_frontend.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Shared JSON config: lenient on reads, omits nulls and defaults on writes. */
internal val appJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

@Serializable
data class RegisterRequest(
    @SerialName("age_at_signup") val ageAtSignup: Int,
    val gender: String,
    @SerialName("university_major") val universityMajor: String,
    @SerialName("test_score") val testScore: Int,
    @SerialName("experiment_group") val experimentGroup: String,
    // Omitted from the wire for the control group (explicitNulls = false).
    @SerialName("experiment_config") val experimentConfig: JsonObject? = null,
)
