package com.example.smartuse_frontend.network

import com.example.smartuse_frontend.network.dto.ErrorResponse

sealed interface NetworkResult<out T> {
    data class Success<T>(val body: T) : NetworkResult<T>

    /** [rawBody] is the response text exactly as received, kept for diagnostics even when it isn't an [ErrorResponse]. */
    data class HttpError(
        val code: Int,
        val errorBody: ErrorResponse?,
        val rawBody: String? = null,
    ) : NetworkResult<Nothing>

    data class IoError(val cause: Throwable) : NetworkResult<Nothing>
}
