package com.example.smartuse_frontend.network

import com.example.smartuse_frontend.network.dto.CutoffResponse
import com.example.smartuse_frontend.network.dto.FinalSendRequest
import com.example.smartuse_frontend.network.dto.FinalSendResponse
import com.example.smartuse_frontend.network.dto.RegisterRequest
import com.example.smartuse_frontend.network.dto.RegisterResponse

interface SmartUseApi {
    /** POST /users — no auth. Never call twice for one install (no idempotency key). */
    suspend fun register(body: RegisterRequest): NetworkResult<RegisterResponse>

    /** GET /cutoff-deployment-group: no auth, the same for every participant. This group's end date, or null. */
    suspend fun getCutoff(): NetworkResult<CutoffResponse>

    /** POST /final, Bearer auth: the end forms and the metrics, once per participant; idempotent on the backend. */
    suspend fun sendFinal(token: String, body: FinalSendRequest): NetworkResult<FinalSendResponse>
}
