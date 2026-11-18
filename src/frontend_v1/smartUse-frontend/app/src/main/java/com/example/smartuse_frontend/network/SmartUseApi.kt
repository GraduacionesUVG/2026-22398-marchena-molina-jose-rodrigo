package com.example.smartuse_frontend.network

import com.example.smartuse_frontend.network.dto.CanonicalCursorResponse
import com.example.smartuse_frontend.network.dto.CanonicalSyncRequest
import com.example.smartuse_frontend.network.dto.CanonicalSyncResponse
import com.example.smartuse_frontend.network.dto.CutoffResponse
import com.example.smartuse_frontend.network.dto.OptOutAckResponse
import com.example.smartuse_frontend.network.dto.RegisterRequest
import com.example.smartuse_frontend.network.dto.RegisterResponse

interface SmartUseApi {
    /** POST /users — no auth. Never call twice for one install (no idempotency key). */
    suspend fun register(body: RegisterRequest): NetworkResult<RegisterResponse>

    /** POST /sync/canonical — bearer auth. One all-or-nothing batch of closed epochs. */
    suspend fun syncCanonical(authToken: String, body: CanonicalSyncRequest): NetworkResult<CanonicalSyncResponse>

    /** GET /sync/canonical/cursor — bearer auth. The server's current watermark for this subject. */
    suspend fun getCanonicalCursor(authToken: String): NetworkResult<CanonicalCursorResponse>

    /** GET /cutoff: no auth, the same for every participant. The study-wide end date, or null. */
    suspend fun getCutoff(): NetworkResult<CutoffResponse>

    /** POST /opt-out/soft — bearer auth, no request body. Client-only stop: sent data is kept. */
    suspend fun optOutSoft(authToken: String): NetworkResult<OptOutAckResponse>

    /** POST /opt-out/hard — bearer auth, no request body. Also deletes stored data server-side. */
    suspend fun optOutHard(authToken: String): NetworkResult<OptOutAckResponse>
}
