package com.example.smartuse_frontend.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricRecorderTest {

    private class FakeReadings : MetricReadings {
        var battery: Int? = 80
        var current: Int? = -300
        var rss: Long? = 1_000
        var throwing = false
        override fun batteryPct(): Int? = if (throwing) error("battery") else battery
        override fun currentNow(): Int? = if (throwing) error("current") else current
        override fun rssKb(): Long? = if (throwing) error("rss") else rss
    }

    private val readings = FakeReadings()
    private var nowMs = 1_000L
    private val recorder = MetricRecorder(readings, elapsedMs = { nowMs })

    @Test
    fun `duration is the monotonic time from start to finish`() {
        recorder.start()
        nowMs = 1_412
        assertEquals(412L, recorder.finish(DecisionKind.SCREEN_ON).durationMs)
    }

    @Test
    fun `battery delta is start minus end - positive drains, negative charges`() {
        readings.battery = 80
        recorder.start()
        readings.battery = 79
        assertEquals(1, recorder.finish(DecisionKind.SCREEN_ON).batteryDeltaPct)

        val charging = MetricRecorder(readings, elapsedMs = { nowMs })
        readings.battery = 79
        charging.start()
        readings.battery = 80
        assertEquals(-1, charging.finish(DecisionKind.SCREEN_ON).batteryDeltaPct)
    }

    @Test
    fun `battery delta is NULL when the start or the end is missing or out of range`() {
        readings.battery = null
        recorder.start()
        readings.battery = 79
        assertNull(recorder.finish(DecisionKind.SCREEN_ON).batteryDeltaPct)

        val endMissing = MetricRecorder(readings, elapsedMs = { nowMs })
        readings.battery = 80
        endMissing.start()
        readings.battery = Int.MIN_VALUE
        assertNull(endMissing.finish(DecisionKind.SCREEN_ON).batteryDeltaPct)
    }

    @Test
    fun `current mean averages valid readings only, MIN_VALUE and 0 excluded`() {
        readings.current = -300
        recorder.start()
        readings.current = Int.MIN_VALUE
        recorder.sample()
        readings.current = 0
        recorder.sample()
        readings.current = -500
        assertEquals(-400, recorder.finish(DecisionKind.SCREEN_ON).currentMean)
    }

    @Test
    fun `a device without a fuel gauge gives NULLs, not garbage`() {
        readings.current = 0
        readings.battery = Int.MIN_VALUE
        recorder.start()
        recorder.sample()
        readings.current = Int.MIN_VALUE
        val metric = recorder.finish(DecisionKind.SCREEN_ON)
        assertNull(metric.currentMean)
        assertNull(metric.batteryDeltaPct)
    }

    @Test
    fun `peak RSS is the maximum over every reading, including the post-model sample`() {
        readings.rss = 1_000
        recorder.start()
        readings.rss = 5_000
        recorder.sample()
        readings.rss = 2_000
        assertEquals(5_000L, recorder.finish(DecisionKind.SCREEN_ON).peakRssKb)
    }

    @Test
    fun `peak RSS is NULL when no reading had VmRSS`() {
        readings.rss = null
        recorder.start()
        assertNull(recorder.finish(DecisionKind.SCREEN_ON).peakRssKb)
    }

    @Test
    fun `failing readings are skipped, never thrown`() {
        readings.throwing = true
        recorder.start()
        recorder.sample()
        val metric = recorder.finish(DecisionKind.SCREEN_ON)
        assertNull(metric.batteryDeltaPct)
        assertNull(metric.currentMean)
        assertNull(metric.peakRssKb)
    }

    @Test
    fun `the record carries the decision point's kind`() {
        recorder.start()
        assertEquals(DecisionKind.ACTIVE_USE, recorder.finish(DecisionKind.ACTIVE_USE).decisionPoint)
    }

    @Test
    fun `the wire names are the spec's`() {
        assertEquals(listOf("screen_on", "active_use"), DecisionKind.entries.map { it.wire })
    }
}
