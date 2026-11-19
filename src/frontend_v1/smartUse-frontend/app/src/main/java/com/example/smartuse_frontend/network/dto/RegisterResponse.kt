package com.example.smartuse_frontend.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterResponse(
    @SerialName("user_id") val userId: String,
    @SerialName("auth_token") val authToken: String,
)
