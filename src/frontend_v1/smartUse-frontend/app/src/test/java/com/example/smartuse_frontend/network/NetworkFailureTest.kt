package com.example.smartuse_frontend.network

import com.example.smartuse_frontend.network.dto.ErrorResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class NetworkFailureTest {

    private fun http(code: Int, error: String? = null) =
        NetworkResult.HttpError(code, error?.let { ErrorResponse(error = it) }, null)

    @Test
    fun `network errors are transient`() {
        assertEquals(FailureKind.TRANSIENT, NetworkResult.IoError(IOException("x")).failureKind())
    }

    @Test
    fun `server errors, timeouts and throttling are transient`() {
        listOf(500, 502, 503, 504, 408, 429).forEach {
            assertEquals("HTTP $it", FailureKind.TRANSIENT, http(it).failureKind())
        }
    }

    @Test
    fun `403 capture_period_ended means the study is over`() {
        assertEquals(FailureKind.STUDY_OVER, http(403, "capture_period_ended").failureKind())
    }

    @Test
    fun `other client errors are permanent, including other 403s`() {
        assertEquals(FailureKind.PERMANENT, http(403).failureKind())
        assertEquals(FailureKind.PERMANENT, http(400, "validation_error").failureKind())
        assertEquals(FailureKind.PERMANENT, http(401).failureKind())
        assertEquals(FailureKind.PERMANENT, http(404).failureKind())
        assertEquals(FailureKind.PERMANENT, http(409, "epoch_conflict").failureKind())
    }

    @Test
    fun `a success has no failure kind`() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkResult.Success("ok").failureKind()
        }
    }
}
