package com.example.smartuse_frontend.metrics

/** One reading's raw values; null when the device can't provide one. Validity is CycleRecorder's call. */
interface MetricReadings {
    fun batteryPct(): Int?
    fun currentNow(): Int?
    fun rssKb(): Long?
}

/** The process's resident memory from `/proc/self/status` (`VmRSS:   98304 kB`); null when absent or unparsable. */
fun parseVmRssKb(status: String): Long? = status.lineSequence()
    .firstOrNull { it.startsWith("VmRSS:") }
    ?.removePrefix("VmRSS:")
    ?.trim()
    ?.removeSuffix("kB")
    ?.trim()
    ?.toLongOrNull()
