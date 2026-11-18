package com.example.smartuse_frontend.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiEndpointTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        ApiEndpoint.overrideForTests(null)
        server.shutdown()
    }

    @Test
    fun `a client built after an override talks to the overridden base url`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"cutoff":null}"""))
        ApiEndpoint.overrideForTests(server.url("/prod").toString())

        SmartUseApiImpl(logger = {}).getCutoff()

        assertEquals("/prod/cutoff", server.takeRequest().path)
    }

    @Test
    fun `clearing the override restores the built-in url`() {
        ApiEndpoint.overrideForTests("http://127.0.0.1:1/x")
        ApiEndpoint.overrideForTests(null)

        assertEquals(com.example.smartuse_frontend.BuildConfig.SMARTUSE_BASE_URL, ApiEndpoint.baseUrl)
    }
}
