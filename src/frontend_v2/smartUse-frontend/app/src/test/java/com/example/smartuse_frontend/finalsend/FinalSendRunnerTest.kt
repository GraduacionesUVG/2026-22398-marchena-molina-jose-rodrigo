package com.example.smartuse_frontend.finalsend

import com.example.smartuse_frontend.forms.CompletedForm
import com.example.smartuse_frontend.forms.CompletedForms
import com.example.smartuse_frontend.forms.FakeFormDraftStore
import com.example.smartuse_frontend.jobs.FakeJobStateStore
import com.example.smartuse_frontend.metrics.DecisionKind
import com.example.smartuse_frontend.metrics.DecisionMetric
import com.example.smartuse_frontend.network.NetworkResult
import com.example.smartuse_frontend.network.SmartUseApi
import com.example.smartuse_frontend.network.dto.CutoffResponse
import com.example.smartuse_frontend.network.dto.ErrorResponse
import com.example.smartuse_frontend.network.dto.FinalSendRequest
import com.example.smartuse_frontend.network.dto.FinalSendResponse
import com.example.smartuse_frontend.network.dto.RegisterRequest
import com.example.smartuse_frontend.network.dto.RegisterResponse
import com.example.smartuse_frontend.notification.FakeStudyNotifier
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.study.EndingCleanup
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

private class FinalApi(private val answer: () -> NetworkResult<FinalSendResponse>) : SmartUseApi {
    val sent = mutableListOf<Pair<String, FinalSendRequest>>()

    override suspend fun register(body: RegisterRequest): NetworkResult<RegisterResponse> = error("not used")
    override suspend fun getCutoff(): NetworkResult<CutoffResponse> = error("not used")

    override suspend fun sendFinal(token: String, body: FinalSendRequest): NetworkResult<FinalSendResponse> {
        sent += token to body
        return answer()
    }
}

class FinalSendRunnerTest {

    private val now = Instant.parse("2026-12-04T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val drafts = FakeFormDraftStore()
    private val notifier = FakeStudyNotifier()
    private val logs = mutableListOf<String>()
    private var wipes = 0
    private var stops = 0
    private val metrics = listOf(
        DecisionMetric(DecisionKind.SCREEN_ON, 400, 0, -1, 9000),
        DecisionMetric(DecisionKind.ACTIVE_USE, 30, null, null, null),
    )

    private suspend fun formsDone(optedOut: Boolean = false): FakeJobStateStore {
        drafts.saveCompleted(CompletedForms(listOf(CompletedForm("usability", 2, buildJsonObject { put("d1_1", 4) }))))
        return if (optedOut) FakeJobStateStore(optedOutAt = now, formsCompletedAt = now)
        else FakeJobStateStore(collectionEndedAt = now, formsCompletedAt = now)
    }

    private fun runner(
        store: FakeJobStateStore,
        api: SmartUseApi,
        readMetrics: () -> List<DecisionMetric> = { metrics },
    ) = FinalSendRunner(
        stateStore = store,
        forms = drafts,
        readMetrics = readMetrics,
        authToken = { "tok-9" },
        api = api,
        cleanup = EndingCleanup(store, drafts, {}, { wipes++ }, { stops++ }, notifier, clock, {}),
        clock = clock,
        random = Random(7),
        logger = { logs += it },
    )

    @Test
    fun `201 and 200 both confirm - marked, erased, forms cleared, service stopped, end notice`() = runTest {
        for (status in listOf("stored", "already_received")) {
            val store = formsDone()
            val api = FinalApi { NetworkResult.Success(FinalSendResponse(status)) }
            wipes = 0

            assertEquals(FinalSendResult.DONE, runner(store, api).run())

            val (token, body) = api.sent.single()
            assertEquals("tok-9", token)
            assertEquals("completed", body.endReason)
            assertEquals(2, body.metrics.size)
            assertEquals(2, body.forms.getValue("usability").version)
            assertEquals(now, store.finalSentAt())
            assertEquals(1, wipes)
            assertNull(drafts.completed())
            assertEquals(StudyNotice.Finished(optedOut = false), notifier.shown.last())
        }
    }

    @Test
    fun `an opt-out is sent as opt_out and ends with the opt-out notice`() = runTest {
        val api = FinalApi { NetworkResult.Success(FinalSendResponse("stored")) }

        runner(formsDone(optedOut = true), api).run()

        assertEquals("opt_out", api.sent.single().second.endReason)
        assertEquals(StudyNotice.Finished(optedOut = true), notifier.shown.last())
    }

    @Test
    fun `network errors, 5xx and 429 retry and erase nothing`() = runTest {
        val failures = listOf<NetworkResult<FinalSendResponse>>(
            NetworkResult.IoError(IOException("offline")),
            NetworkResult.HttpError(500, null),
            NetworkResult.HttpError(429, null),
        )
        for (failure in failures) {
            val store = formsDone()

            assertEquals(FinalSendResult.RETRY, runner(store, FinalApi { failure }).run())

            assertNull(store.finalSentAt())
            assertNotNull(drafts.completed())
        }
        assertEquals(0, wipes)
    }

    @Test
    fun `400 and 401 retry too, logged with the status, and never drop the forms`() = runTest {
        for (code in listOf(400, 401)) {
            val store = formsDone()
            val api = FinalApi { NetworkResult.HttpError(code, ErrorResponse(error = "invalid_body")) }

            assertEquals(FinalSendResult.RETRY, runner(store, api).run())

            assertTrue(logs.any { it.contains("HTTP $code") })
            assertNull(store.finalSentAt())
            assertNotNull(drafts.completed())
        }
    }

    @Test
    fun `a metrics read failure retries without calling the backend`() = runTest {
        val api = FinalApi { NetworkResult.Success(FinalSendResponse("stored")) }

        val result = runner(formsDone(), api, readMetrics = { throw IllegalStateException("db locked") }).run()

        assertEquals(FinalSendResult.RETRY, result)
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun `nothing to send outside FORMS_DONE`() = runTest {
        val api = FinalApi { error("must not be called") }

        assertEquals(FinalSendResult.DONE, runner(FakeJobStateStore(), api).run())
        assertEquals(FinalSendResult.DONE, runner(FakeJobStateStore(collectionEndedAt = now), api).run())
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun `already sent only finishes the cleanup`() = runTest {
        val store = FakeJobStateStore(collectionEndedAt = now, formsCompletedAt = now, finalSentAt = now)
        val api = FinalApi { error("must not be called") }

        assertEquals(FinalSendResult.DONE, runner(store, api).run())

        assertTrue(api.sent.isEmpty())
        assertEquals(1, wipes)
        assertEquals(1, stops)
    }
}