package com.example.smartuse_frontend.network

import com.example.smartuse_frontend.network.dto.CanonicalRecordDto
import com.example.smartuse_frontend.network.dto.CanonicalSyncRequest
import com.example.smartuse_frontend.network.dto.CutoffResponse
import com.example.smartuse_frontend.network.dto.RegisterRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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

    private fun canonicalRequest() = CanonicalSyncRequest(
        records = listOf(
            CanonicalRecordDto(
                clientRecordId = "6f1c2b1e-0a0b-5c0d-8e0f-123456789abc",
                startTs = 1_700_000_000_123,
                isUse = true,
                distinctAppCount = 3,
                granularity = "small",
            ),
        ),
    )

    private val syncAckBody =
        """{"received":1,"synced_through":{"client_record_id":"c-1","start_ts":"2023-11-14T22:13:20.123Z"}}"""

    private fun sampleRequest() = RegisterRequest(
        ageAtSignup = 21,
        gender = "Femenino",
        universityMajor = "Psicología",
        testScore = 34,
        experimentGroup = "control",
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
    fun `syncCanonical posts a bearer-authenticated body with ISO start_ts and granularity`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(syncAckBody))

        api().syncCanonical("tok-9", canonicalRequest())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.endsWith("/prod/sync/canonical"))
        assertEquals("Bearer tok-9", recorded.getHeader("Authorization"))
        val record = Json.parseToJsonElement(recorded.body.readUtf8())
            .jsonObject["records"]!!.jsonArray[0].jsonObject
        assertEquals("6f1c2b1e-0a0b-5c0d-8e0f-123456789abc", record["client_record_id"]!!.jsonPrimitive.content)
        assertTrue(record["start_ts"]!!.jsonPrimitive.isString)
        assertEquals("2023-11-14T22:13:20.123Z", record["start_ts"]!!.jsonPrimitive.content)
        assertEquals(true, record["is_use"]!!.jsonPrimitive.boolean)
        assertEquals(3, record["distinct_app_count"]!!.jsonPrimitive.int)
        assertEquals("small", record["granularity"]!!.jsonPrimitive.content)
    }

    @Test
    fun `syncCanonical parses synced_through with ISO start_ts into epoch millis`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(syncAckBody))

        val result = api().syncCanonical("tok", canonicalRequest())

        assertTrue(result is NetworkResult.Success)
        result as NetworkResult.Success
        assertEquals(1, result.body.received)
        assertEquals(1_700_000_000_123, result.body.syncedThrough?.startTs)
        assertEquals("c-1", result.body.syncedThrough?.clientRecordId)
    }

    @Test
    fun `syncCanonical accepts a null synced_through`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"received":0,"synced_through":null}"""))

        val result = api().syncCanonical("tok", canonicalRequest())

        assertTrue(result is NetworkResult.Success)
        assertNull((result as NetworkResult.Success).body.syncedThrough)
    }

    // --- GET /sync/canonical/cursor -------------------------------------------

    @Test
    fun `getCanonicalCursor issues an authenticated GET and parses the flat response`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"client_record_id":"c-7","start_ts":"2023-11-14T22:13:20.123Z"}"""),
        )

        val result = api().getCanonicalCursor("tok-3")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertTrue(recorded.path!!.endsWith("/prod/sync/canonical/cursor"))
        assertEquals("Bearer tok-3", recorded.getHeader("Authorization"))
        assertTrue(result is NetworkResult.Success)
        result as NetworkResult.Success
        assertEquals("c-7", result.body.clientRecordId)
        assertEquals(1_700_000_000_123, result.body.startTs)
    }

    @Test
    fun `getCanonicalCursor also accepts offset timestamps with microsecond precision`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"client_record_id":"c-7","start_ts":"2023-11-14T22:13:20.123456+00:00"}"""),
        )

        val result = api().getCanonicalCursor("tok")

        assertTrue(result is NetworkResult.Success)
        assertEquals(1_700_000_000_123, (result as NetworkResult.Success).body.startTs)
    }

    @Test
    fun `getCanonicalCursor maps null fields to null`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"client_record_id":null,"start_ts":null}"""))

        val result = api().getCanonicalCursor("tok")

        assertTrue(result is NetworkResult.Success)
        result as NetworkResult.Success
        assertNull(result.body.clientRecordId)
        assertNull(result.body.startTs)
    }

    // --- error visibility ------------------------------------------------------

    private val conflictBody = """{"error":"epoch_conflict","message":"start_ts collision"}"""

    @Test
    fun `HttpError keeps the raw body and logs method, url, status and body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody(conflictBody))

        val result = api().syncCanonical("tok-secret", canonicalRequest())

        assertTrue(result is NetworkResult.HttpError)
        result as NetworkResult.HttpError
        assertEquals(409, result.code)
        assertEquals(conflictBody, result.rawBody)
        val logged = logs.joinToString("\n")
        assertTrue(logged, logged.contains("POST"))
        assertTrue(logged, logged.contains("/prod/sync/canonical"))
        assertTrue(logged, logged.contains("409"))
        assertTrue(logged, logged.contains(conflictBody))
    }

    @Test
    fun `a failed sync also logs the request body that was sent`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"validation_error"}"""))

        api().syncCanonical("tok", canonicalRequest())

        val logged = logs.joinToString("\n")
        assertTrue(logged, logged.contains("6f1c2b1e-0a0b-5c0d-8e0f-123456789abc"))
    }

    @Test
    fun `logs never contain the bearer token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}"""))

        api().syncCanonical("tok-secret", canonicalRequest())

        assertFalse(logs.joinToString("\n").contains("tok-secret"))
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

        val result = api().syncCanonical("tok", canonicalRequest())

        assertTrue(result is NetworkResult.IoError)
        val logged = logs.joinToString("\n")
        assertTrue(logged, logged.contains("definitely not json"))
    }

    @Test
    fun `a connection failure is logged with the exception`() = runBlocking {
        server.shutdown()

        api().getCanonicalCursor("tok")

        val logged = logs.joinToString("\n")
        assertTrue(logged, logged.contains("/prod/sync/canonical/cursor"))
        assertTrue(logged, logged.contains("Exception"))
    }

    // --- POST /opt-out/soft, /opt-out/hard --------------------------------

    @Test
    fun `optOutSoft posts to the soft endpoint with bearer auth`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"capture_status":"opted_out_soft","opted_out_at":"2026-09-22T10:00:00Z"}"""),
        )

        val result = api().optOutSoft("tok-5")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.endsWith("/prod/opt-out/soft"))
        assertEquals("Bearer tok-5", recorded.getHeader("Authorization"))
        assertTrue(result is NetworkResult.Success)
        assertEquals("opted_out_soft", (result as NetworkResult.Success).body.captureStatus)
    }

    @Test
    fun `optOutHard posts to the hard endpoint`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"capture_status":"opted_out_hard","opted_out_at":"2026-09-22T10:00:00Z"}"""),
        )

        api().optOutHard("tok-6")

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.endsWith("/prod/opt-out/hard"))
    }

    @Test
    fun `a failed hard opt-out maps to HttpError`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}"""))

        val result = api().optOutHard("tok-bad")

        assertTrue(result is NetworkResult.HttpError)
        assertEquals(401, (result as NetworkResult.HttpError).code)
    }

    @Test
    fun `getCutoff is an unauthenticated GET that parses a date`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cutoff":"2026-12-01"}"""))

        val result = api().getCutoff()

        assertEquals(NetworkResult.Success(CutoffResponse("2026-12-01")), result)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/prod/cutoff", recorded.path)
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

    @Test
    fun `a 409 epoch_conflict body keeps the colliding records`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"error":"epoch_conflict","message":"collision","conflicts":[{"client_record_id":"c-1","start_ts":"2026-09-20T10:00:00Z"}]}""",
            ),
        )

        val result = api().syncCanonical("tok", canonicalRequest())

        result as NetworkResult.HttpError
        assertEquals("epoch_conflict", result.errorBody?.error)
        assertEquals("c-1", result.errorBody?.conflicts?.single()?.clientRecordId)
        assertEquals("2026-09-20T10:00:00Z", result.errorBody?.conflicts?.single()?.startTs)
    }
}
