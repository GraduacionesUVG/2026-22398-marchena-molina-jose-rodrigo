package com.example.smartuse_frontend.e2e

/** Polls [probe] until it returns non-null; fails with [what] after [timeoutMs]. */
fun <T : Any> await(what: String, timeoutMs: Long = 30_000, pollMs: Long = 500, probe: () -> T?): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        probe()?.let { return it }
        if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out after ${timeoutMs} ms waiting for: $what")
        Thread.sleep(pollMs)
    }
}
