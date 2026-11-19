package com.example.smartuse_frontend.e2e

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.time.Instant

private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val UNAUTHENTICATED = setOf("POST /users", "GET /cutoff")

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

        var lastAck: Instant? = null
        val idByStart = mutableMapOf<String, String>()
        calls.filter { it.key == "POST /sync/canonical" }.forEach { call ->
            val records = JSONObject(call.body).getJSONArray("records")
            assertTrue("batch of ${records.length()} records over 500", records.length() <= 500)
            var previous: Instant? = null
            for (i in 0 until records.length()) {
                val r = records.getJSONObject(i)
                val id = r.getString("client_record_id")
                val start = Instant.parse(r.getString("start_ts"))
                assertTrue("client_record_id $id is not a UUID", UUID_RE.matches(id))
                assertEquals("granularity of $id", "small", r.getString("granularity"))
                previous?.let { assertTrue("records not ascending at $id", start > it) }
                lastAck?.let { assertTrue("record $id not after the acknowledged cursor", start > it) }
                idByStart.put(r.getString("start_ts"), id)?.let { assertEquals("id of the epoch at $start changed", it, id) }
                previous = start
            }
            // A non-2xx stored nothing (all-or-nothing): the app must resend, the cursor stays.
            if (call.acknowledged && records.length() > 0) lastAck = previous
        }

        backend.conflicted.forEach { id ->
            val named = calls.indexOfFirst { it.key == "POST /sync/canonical" && it.status == 409 && id in it.recordIds() }
            calls.drop(named + 1).filter { it.key == "POST /sync/canonical" }.forEach {
                assertTrue("epoch $id resent after the 409 that named it", id !in it.recordIds())
            }
        }

        val sync = calls.filter { it.path.startsWith("/sync/canonical") }
        if (sync.isNotEmpty()) {
            assertEquals("a sync run starts with GET cursor", "GET /sync/canonical/cursor", sync.first().key)
            assertEquals("a sync run ends with GET cursor", "GET /sync/canonical/cursor", sync.last().key)
        }
    }
}
