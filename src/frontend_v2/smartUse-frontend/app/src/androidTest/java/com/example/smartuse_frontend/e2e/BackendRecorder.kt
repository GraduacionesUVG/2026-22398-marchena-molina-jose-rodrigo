package com.example.smartuse_frontend.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

private val UNAUTHENTICATED = setOf("POST /users", "GET /cutoff-deployment-group")

/** Contract checks over everything the app sent to the fake backend, run at the end of every test. */
object BackendRecorder {

    fun callsAfter(backend: FakeBackend, at: Long): List<RecordedCall> = backend.calls.filter { it.at > at }

    fun assertContract(backend: FakeBackend) {
        val calls = backend.calls.toList()
        calls.filter { it.key !in UNAUTHENTICATED }.forEach {
            assertEquals("bearer token on ${it.key}", "Bearer ${backend.token}", it.authorization)
        }
        calls.filter { it.method == "POST" && it.body.isNotEmpty() }.forEach {
            assertTrue("JSON content type on ${it.key}: ${it.contentType}", it.contentType?.startsWith("application/json") == true)
        }
    }
}
