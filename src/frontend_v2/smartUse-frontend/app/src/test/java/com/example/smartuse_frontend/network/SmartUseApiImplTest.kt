package com.example.smartuse_frontend.network

import com.example.smartuse_frontend.network.dto.CutoffResponse
import com.example.smartuse_frontend.network.dto.FinalForm
import com.example.smartuse_frontend.network.dto.FinalSendRequest
import com.example.smartuse_frontend.network.dto.MetricRecord
import com.example.smartuse_frontend.network.dto.RegisterRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

class SmartUseApiImplTest {

    private lateinit var server: MockWebServer
    private val logs = mutableListOf<String>()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun api() = SmartUseApiImpl(
        baseUrl = server.url("/prod").toString(),
        logger = { logs += it },
    )

    private fun sampleRequest() = RegisterRequest(
        ageAtSignup = 21,
        gender = "Femenino",
        universityMajor = "Psicología",
        testScore = 34,
        experimentGroup = "inference",
    )

    @Test
    fun `201 maps to Success with parsed body`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"user_id":"u-1","auth_token":"t-1"}"""),
        )

        val result = api().register(sampleRequest())

        assertTrue(result is NetworkResult.Success)
        result as NetworkResult.Success
        assertEquals("u-1", result.body.userId)
        assertEquals("t-1", result.body.authToken)
    }

    @Test
    fun `request path is users and omits experiment_config`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"user_id":"u","auth_token":"t"}"""))

        api().register(sampleRequest())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.endsWith("/prod/users"))
        assertFalse(recorded.body.readUtf8().contains("experiment_config"))
    }

    @Test
    fun `400 maps to HttpError with parsed error body`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":"validation","message":"age_at_signup missing"}"""),
        )

        val result = api().register(sampleRequest())

        assertTrue(result is NetworkResult.HttpError)
        result as NetworkResult.HttpError
        assertEquals(400, result.code)
        assertEquals("age_at_signup missing", result.errorBody?.message)
    }

    @Test
    fun `500 maps to HttpError even with an unparseable body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>oops</html>"))

        val result = api().register(sampleRequest())

        assertTrue(result is NetworkResult.HttpError)
        assertEquals(500, (result as NetworkResult.HttpError).code)
    }

    @Test
    fun `connection failure maps to IoError`() = runBlocking {
        server.shutdown() // nothing listening

        val result = api().register(sampleRequest())

        assertTrue(result is NetworkResult.IoError)
    }

    // --- POST /sync/canonical ------------------------------------------------

    @Test
    fun `HttpError keeps the raw body and logs method, url, status and body`() = runBlocking {
        val body = """{"error":"internal","message":"boom"}"""
        server.enqueue(MockResponse().setResponseCode(500).setBody(body))

        val result = api().getCutoff()

        assertTrue(result is NetworkResult.HttpError)
        result as NetworkResult.HttpError
        assertEquals(500, result.code)
        assertEquals(body, result.rawBody)
        val logged = logs.joinToString("\n")
        assertTrue(logged, logged.contains("GET"))
        assertTrue(logged, logged.contains("/prod/cutoff-deployment-group"))
        assertTrue(logged, logged.contains("500"))
        assertTrue(logged, logged.contains(body))
    }

    @Test
    fun `a failed register does not log the request body (profile data)`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"validation_error"}"""))

        api().register(sampleRequest())

        val logged = logs.joinToString("\n")
        assertTrue(logged, logged.contains("400"))
        assertFalse(logged, logged.contains("Psicología"))
    }

    @Test
    fun `a 2xx body that cannot be parsed maps to IoError and logs the raw body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("definitely not json"))

        val result = api().getCutoff()

        assertTrue(result is NetworkResult.IoError)
        val logged = logs.joinToString("\n")
        assertTrue(logged, logged.contains("definitely not json"))
    }

    @Test
    fun `a connection failure is logged with the exception`() = runBlocking {
        server.shutdown()

        api().getCutoff()

        val logged = logs.joinToString("\n")
        assertTrue(logged, logged.contains("/prod/cutoff-deployment-group"))
        assertTrue(logged, logged.contains("Exception"))
    }

    @Test
    fun `getCutoff is an unauthenticated GET that parses a date`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cutoff":"2026-12-01"}"""))

        val result = api().getCutoff()

        assertEquals(NetworkResult.Success(CutoffResponse("2026-12-01")), result)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/prod/cutoff-deployment-group", recorded.path)
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `getCutoff parses a null cutoff`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cutoff":null}"""))

        assertEquals(NetworkResult.Success(CutoffResponse(null)), api().getCutoff())
    }

    @Test
    fun `a failed getCutoff maps to HttpError`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))

        val result = api().getCutoff()

        assertTrue(result is NetworkResult.HttpError)
        assertEquals(503, (result as NetworkResult.HttpError).code)
    }

    @Test
    fun `a 200 without the cutoff key means no cutoff is set`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        assertEquals(NetworkResult.Success(CutoffResponse(null)), api().getCutoff())
    }

    @Test
    fun `getCutoff gives up after its own short timeout`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cutoff":null}""").setHeadersDelay(3, TimeUnit.SECONDS))
        val api = SmartUseApiImpl(baseUrl = server.url("/prod").toString(), logger = {}, cutoffTimeout = Duration.ofMillis(300))

        val started = System.nanoTime()
        val result = api.getCutoff()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(result is NetworkResult.IoError)
        assertTrue("took $elapsedMs ms", elapsedMs < 2_000)
    }

    @Test
    fun `getCutoff caps the whole call, not just each read`() = runBlocking {
        // Each byte arrives within the read timeout, but the whole body takes seconds.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"cutoff":null}                    """)
                .throttleBody(1, 200, TimeUnit.MILLISECONDS),
        )
        val api = SmartUseApiImpl(baseUrl = server.url("/prod").toString(), logger = {}, cutoffTimeout = Duration.ofMillis(300))

        val started = System.nanoTime()
        val result = api.getCutoff()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("$result", result is NetworkResult.IoError)
        assertTrue("took $elapsedMs ms", elapsedMs < 2_000)
    }

    private fun finalBody() = FinalSendRequest(
        endReason = "completed",
        forms = mapOf("usability" to FinalForm(version = 2, answers = buildJsonObject { put("d1_1", 3) })),
        metrics = listOf(MetricRecord(decisionPoint = "active_use", durationMs = 20)),
    )

    @Test
    fun `the final send posts to final with the bearer token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"status":"stored"}"""))

        val result = api().sendFinal("tok-1", finalBody())

        assertEquals("stored", (result as NetworkResult.Success).body.status)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.endsWith("/prod/final"))
        assertEquals("Bearer tok-1", recorded.getHeader("Authorization"))
        val sent = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("completed", sent["end_reason"]!!.jsonPrimitive.content)
        assertFalse(sent["metrics"]!!.jsonArray[0].jsonObject.containsKey("battery_delta_pct"))
    }

    @Test
    fun `a rejected final send is an HttpError, logged without the answers or the token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_body","message":"forms"}"""))

        val result = api().sendFinal("tok-1", finalBody())

        assertEquals(400, (result as NetworkResult.HttpError).code)
        assertEquals("invalid_body", result.errorBody?.error)
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { it.contains("\"q1\"") || it.contains("tok-1") })
    }

    // The backend stored it: whatever a 2xx carries, the send is confirmed (no retry loop over a body shape).
    @Test
    fun `a final send 2xx with an empty or unexpected body is still a success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(204))

        repeat(3) {
            val result = api().sendFinal("tok-1", finalBody())
            assertTrue("attempt $it: $result", result is NetworkResult.Success)
        }
    }
}