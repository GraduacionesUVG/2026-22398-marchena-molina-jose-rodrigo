package com.example.smartuse_frontend.network.dto

import kotlinx.serialization.Serializable

/** GET /cutoff. `cutoff` is "YYYY-MM-DD" (00:00 America/Guatemala) or null when none is published. */
@Serializable
data class CutoffResponse(val cutoff: String? = null)
