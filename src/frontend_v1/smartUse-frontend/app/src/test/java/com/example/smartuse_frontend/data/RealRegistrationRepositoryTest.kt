package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.network.dto.CutoffResponse
import com.example.smartuse_frontend.network.NetworkResult
import com.example.smartuse_frontend.network.SmartUseApi
import com.example.smartuse_frontend.network.dto.CanonicalCursorResponse
import com.example.smartuse_frontend.network.dto.CanonicalSyncRequest
import com.example.smartuse_frontend.network.dto.CanonicalSyncResponse
import com.example.smartuse_frontend.network.dto.ErrorResponse
import com.example.smartuse_frontend.network.dto.OptOutAckResponse
import com.example.smartuse_frontend.network.dto.RegisterRequest
import com.example.smartuse_frontend.network.dto.RegisterResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private class StubApi(
    private val result: NetworkResult<RegisterResponse>,
) : SmartUseApi {
    var lastRequest: RegisterRequest? = null
    override suspend fun register(body: RegisterRequest): NetworkResult<RegisterResponse> {
        lastRequest = body
        return result
    }

    override suspend fun syncCanonical(authToken: String, body: CanonicalSyncRequest): NetworkResult<CanonicalSyncResponse> =
        throw NotImplementedError("not exercised by this test class")

    override suspend fun getCanonicalCursor(authToken: String): NetworkResult<CanonicalCursorResponse> =
        throw NotImplementedError("not exercised by this test class")

    override suspend fun getCutoff(): NetworkResult<CutoffResponse> =
        throw NotImplementedError("not exercised by this test class")
    override suspend fun optOutSoft(authToken: String): NetworkResult<OptOutAckResponse> =
        throw NotImplementedError("not exercised by this test class")

    override suspend fun optOutHard(authToken: String): NetworkResult<OptOutAckResponse> =
        throw NotImplementedError("not exercised by this test class")
}

class RealRegistrationRepositoryTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-08-29T09:30:00Z"), ZoneOffset.UTC)

    private fun controlDraft() = SetupDraft(
        age = 21, gender = "Femenino", universityMajor = "Psicología",
        sasSvScore = 34, monthOption = "Julio",
    )

    @Test
    fun `success maps to Registered and stamps signup ts from the clock`() = runTest {
        val api = StubApi(NetworkResult.Success(RegisterResponse("u-7", "tok-7")))
        val outcome = RealRegistrationRepository(api, fixedClock).register(controlDraft())

        assertTrue(outcome is RegisterOutcome.Registered)
        outcome as RegisterOutcome.Registered
        assertEquals("u-7", outcome.userId)
        assertEquals("tok-7", outcome.authToken)
        assertEquals(Instant.parse("2026-08-29T09:30:00Z"), outcome.signupTs)
    }

    @Test
    fun `control payload carries wire group and no experiment_config`() = runTest {
        val api = StubApi(NetworkResult.Success(RegisterResponse("u", "t")))
        RealRegistrationRepository(api, fixedClock).register(controlDraft())

        val sent = api.lastRequest!!
        assertEquals("control", sent.experimentGroup)
        assertEquals(21, sent.ageAtSignup)
        assertEquals(34, sent.testScore)
        assertNull(sent.experimentConfig)
    }

    @Test
    fun `http 400 maps to Rejected with the server message`() = runTest {
        val api = StubApi(NetworkResult.HttpError(400, ErrorResponse("validation", "bad age")))
        val outcome = RealRegistrationRepository(api, fixedClock).register(controlDraft())

        assertTrue(outcome is RegisterOutcome.Rejected)
        assertEquals("bad age", (outcome as RegisterOutcome.Rejected).message)
    }

    @Test
    fun `http 500 and io errors map to Retryable`() = runTest {
        val repo500 = RealRegistrationRepository(
            StubApi(NetworkResult.HttpError(500, null)), fixedClock,
        )
        val repoIo = RealRegistrationRepository(
            StubApi(NetworkResult.IoError(RuntimeException("boom"))), fixedClock,
        )

        assertTrue(repo500.register(controlDraft()) is RegisterOutcome.Retryable)
        assertTrue(repoIo.register(controlDraft()) is RegisterOutcome.Retryable)
    }
}
