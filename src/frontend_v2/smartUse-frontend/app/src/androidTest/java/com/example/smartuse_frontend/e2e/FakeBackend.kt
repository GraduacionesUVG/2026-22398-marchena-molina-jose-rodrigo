package com.example.smartuse_frontend.e2e

import com.example.smartuse_frontend.network.ApiEndpoint
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

data class RecordedCall(
    val method: String,
    val path: String,
    val authorization: String?,
    val contentType: String?,
    val body: String,
    val status: Int,
    val at: Long,
) {
    val key: String get() = "$method $path"

    /** The backend accepted it (2xx). */
    val acknowledged: Boolean get() = status in 200..299
}

/**
 * An in-process imitation of the SmartUse backend (behaviour pinned by
 * backend-checks/). Defaults follow the contract; a test can queue its own
 * answer per "METHOD /path". Every call is recorded for BackendRecorder.
 */
class FakeBackend : TestRule {
    val token = "tok-e2e"
    val server = MockWebServer()
    val calls: MutableList<RecordedCall> = CopyOnWriteArrayList()
    @Volatile var cutoff: String? = null

    /** POST /final is idempotent per user: the first one stores (201), every later one is already received (200). */
    @Volatile var finalStored = false
    private val queued = ConcurrentHashMap<String, ConcurrentLinkedQueue<MockResponse>>()

    fun respond(key: String, response: MockResponse) {
        queued.getOrPut(key) { ConcurrentLinkedQueue() }.add(response)
    }

    private val sticky = ConcurrentHashMap<String, Pair<Int, String>>()

    /** Every call to [key] gets this answer until [stopRespondingAlways] (queued answers still go first). */
    fun respondAlways(key: String, code: Int, body: JSONObject = JSONObject().put("error", "e2e_forced_$code")) {
        sticky[key] = code to body.toString()
    }

    fun stopRespondingAlways(key: String) {
        sticky.remove(key)
    }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
            }
            server.start()
            ApiEndpoint.overrideForTests(server.url("/prod").toString().trimEnd('/'))
            try {
                base.evaluate()
            } finally {
                // A dead local port, never the real backend: a worker may still start
                // before the orchestrator ends this process.
                ApiEndpoint.overrideForTests("http://127.0.0.1:9/prod")
                server.shutdown()
            }
        }
    }

    private fun handle(request: RecordedRequest): MockResponse {
        val at = System.currentTimeMillis()
        val method = request.method.orEmpty()
        val path = request.path.orEmpty().removePrefix("/prod").substringBefore('?')
        val body = request.body.readUtf8()
        val response = respondTo("$method $path")
        calls += RecordedCall(
            method = method,
            path = path,
            authorization = request.getHeader("Authorization"),
            contentType = request.getHeader("Content-Type"),
            body = body,
            status = response.status.split(' ')[1].toInt(),
            at = at,
        )
        return response
    }

    private fun respondTo(key: String): MockResponse {
        queued[key]?.poll()?.let { return it }
        sticky[key]?.let { (code, json) ->
            return MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(json)
        }
        return when (key) {
            "GET /cutoff-deployment-group" -> json(200, JSONObject().put("cutoff", cutoff ?: JSONObject.NULL))
            "POST /users" -> json(201, JSONObject().put("user_id", "u-e2e").put("auth_token", token))
            "POST /final" -> if (finalStored) {
                json(200, JSONObject().put("status", "already_received"))
            } else {
                finalStored = true
                json(201, JSONObject().put("status", "stored"))
            }
            else -> json(404, JSONObject().put("error", "not_found"))
        }
    }

    private fun json(code: Int, body: Any) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())
}
