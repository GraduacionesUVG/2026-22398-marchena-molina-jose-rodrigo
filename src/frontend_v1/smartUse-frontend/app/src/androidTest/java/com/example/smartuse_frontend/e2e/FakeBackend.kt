package com.example.smartuse_frontend.e2e

import com.example.smartuse_frontend.network.ApiEndpoint
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

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

    /** The backend stored it: only a 2xx means every record of this request is kept (all-or-nothing). */
    val acknowledged: Boolean get() = status in 200..299
}

/** The client_record_ids of a POST /sync/canonical body, in order. */
fun RecordedCall.recordIds(): List<String> {
    val records = JSONObject(body).getJSONArray("records")
    return (0 until records.length()).map { records.getJSONObject(it).getString("client_record_id") }
}

data class Cursor(val clientRecordId: String, val startTs: String)

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
    @Volatile var cursor: Cursor? = null
        private set
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

    /** The next POST /sync/canonical gets `409 epoch_conflict` naming its first record (as the backend does). */
    @Volatile var conflictOnNextPost = false

    private val conflictedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** client_record_ids a 409 named. */
    val conflicted: Set<String> get() = conflictedIds

    /** The next acknowledged POST /sync/canonical is stored at once but answered after this delay ("ack lost" when the run is stopped meanwhile). */
    @Volatile var nextAckDelayMs = 0L

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
        val response = respondTo("$method $path", body)
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

    private fun respondTo(key: String, body: String): MockResponse {
        queued[key]?.poll()?.let { return it }
        sticky[key]?.let { (code, json) ->
            return MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(json)
        }
        if (key == "POST /sync/canonical" && conflictOnNextPost) {
            conflictOnNextPost = false
            return conflict(body)
        }
        return when (key) {
            "GET /cutoff" -> json(200, JSONObject().put("cutoff", cutoff ?: JSONObject.NULL))
            "GET /sync/canonical/cursor" -> json(200, cursorJson(cursor))
            "POST /sync/canonical" -> acknowledge(body)
            "POST /users" -> json(201, JSONObject().put("user_id", "u-e2e").put("auth_token", token))
            "POST /opt-out/soft" -> json(200, optOut("opted_out_soft"))
            "POST /opt-out/hard" -> json(200, optOut("opted_out_hard"))
            else -> json(404, JSONObject().put("error", "not_found"))
        }
    }

    private fun acknowledge(body: String): MockResponse {
        val records = JSONObject(body).getJSONArray("records")
        val latest = (0 until records.length()).map { records.getJSONObject(it) }
            .maxByOrNull { Instant.parse(it.getString("start_ts")) }
        if (latest != null) {
            val candidate = Cursor(latest.getString("client_record_id"), latest.getString("start_ts"))
            val current = cursor
            if (current == null || Instant.parse(candidate.startTs) > Instant.parse(current.startTs)) cursor = candidate
        }
        val response = json(200, JSONObject().put("received", records.length()).put("synced_through", cursorJson(cursor, nested = true)))
        val delay = nextAckDelayMs
        if (delay > 0) {
            nextAckDelayMs = 0
            response.setHeadersDelay(delay, TimeUnit.MILLISECONDS)
        }
        return response
    }

    private fun conflict(body: String): MockResponse {
        val first = JSONObject(body).getJSONArray("records").getJSONObject(0)
        val id = first.getString("client_record_id")
        conflictedIds += id
        val named = JSONObject().put("client_record_id", id).put("start_ts", first.getString("start_ts"))
        return json(
            409,
            JSONObject().put("error", "epoch_conflict").put("message", "start_ts collision").put("conflicts", JSONArray().put(named)),
        )
    }

    private fun cursorJson(c: Cursor?, nested: Boolean = false): Any = when {
        c == null && nested -> JSONObject.NULL
        c == null -> JSONObject().put("client_record_id", JSONObject.NULL).put("start_ts", JSONObject.NULL)
        else -> JSONObject().put("client_record_id", c.clientRecordId).put("start_ts", c.startTs)
    }

    private fun optOut(status: String) =
        JSONObject().put("capture_status", status).put("opted_out_at", Instant.now().toString())

    private fun json(code: Int, body: Any) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())

    /** Records the app sent in POST /sync/canonical, in order, as (client_record_id, start_ts); [acknowledgedOnly] keeps only 2xx requests. */
    fun sentRecords(acknowledgedOnly: Boolean = false): List<Pair<String, String>> =
        calls.filter { it.key == "POST /sync/canonical" && (!acknowledgedOnly || it.acknowledged) }.flatMap { call ->
            val array: JSONArray = JSONObject(call.body).getJSONArray("records")
            (0 until array.length()).map { array.getJSONObject(it).let { r -> r.getString("client_record_id") to r.getString("start_ts") } }
        }
}
