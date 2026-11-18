package com.example.smartuse_frontend.inference

import com.example.smartuse_frontend.data.db.RawEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class TailTest {
    private val t = 1_000_000L
    private var nextId = 1L

    private fun on(ts: Long) = RawEvent(nextId++, ts, "SCREEN_INTERACTIVE", null)
    private fun off(ts: Long) = RawEvent(nextId++, ts, "SCREEN_NON_INTERACTIVE", null)
    private fun app(ts: Long, hash: String) = RawEvent(nextId++, ts, "ACTIVITY_RESUMED", hash)
    private fun watched(since: Long, vararg obs: ScreenObservation) = ObservationSnapshot(since, obs.toList())

    @Test
    fun `an epoch running at t is cut at t with only the apps seen before t`() {
        // USM already published past t (screen-off after t): the raw log alone is enough.
        val raw = listOf(on(t - 300_000), app(t - 200_000, "a"), app(t - 100_000, "b"))

        val tail = buildTail(raw, lastRawScreenTs = t - 300_000, rawPublishedPastT = true, ObservationSnapshot.NONE, t)

        assertEquals(listOf(SeriesEpoch(t - 300_000, t, true, 2)), tail)
    }

    @Test
    fun `the service's observations after the last USM event extend the tail`() {
        val raw = listOf(on(t - 600_000))
        val obs = watched(
            since = t - 900_000,
            ScreenObservation(t - 900_000, false),
            ScreenObservation(t - 400_000, false),
            ScreenObservation(t - 100_000, true),
        )

        val tail = buildTail(raw, lastRawScreenTs = t - 600_000, rawPublishedPastT = false, obs, t)

        assertEquals(
            listOf(SeriesEpoch(t - 600_000, t - 400_000, true, 0), SeriesEpoch(t - 100_000, t, true, 0)),
            tail,
        )
    }

    @Test
    fun `the same screen-on seen by USM and by the service is one pickup`() {
        val raw = listOf(on(t - 600_000))
        val obs = watched(t - 900_000, ScreenObservation(t - 599_990, true))

        val tail = buildTail(raw, lastRawScreenTs = t - 600_000, rawPublishedPastT = false, obs, t)

        assertEquals(listOf(SeriesEpoch(t - 600_000, t, true, 0)), tail)
    }

    @Test
    fun `a service that started after the last USM event leaves the gap as non-use`() {
        val raw = listOf(on(t - 600_000), app(t - 500_000, "a"))
        val obs = watched(since = t - 300_000, ScreenObservation(t - 300_000, true))

        val tail = buildTail(raw, lastRawScreenTs = t - 600_000, rawPublishedPastT = false, obs, t)

        assertEquals(
            listOf(SeriesEpoch(t - 600_000, t - 500_000, true, 0), SeriesEpoch(t - 300_000, t, true, 0)),
            tail,
        )
    }

    @Test
    fun `without the service, use ends at the last raw event`() {
        val raw = listOf(on(t - 600_000), app(t - 500_000, "a"))

        val tail = buildTail(raw, lastRawScreenTs = t - 600_000, rawPublishedPastT = false, ObservationSnapshot.NONE, t)

        assertEquals(listOf(SeriesEpoch(t - 600_000, t - 500_000, true, 0)), tail)
    }

    @Test
    fun `a screen-off as the last USM event leaves nothing to stretch`() {
        val raw = listOf(off(t - 600_000))

        assertEquals(emptyList<SeriesEpoch>(), buildTail(raw, t - 600_000, false, ObservationSnapshot.NONE, t))
    }

    @Test
    fun `with no raw history only the service's observations count`() {
        val obs = watched(t - 300_000, ScreenObservation(t - 300_000, true), ScreenObservation(t - 100_000, false))

        val tail = buildTail(emptyList(), lastRawScreenTs = null, rawPublishedPastT = false, obs, t)

        assertEquals(listOf(SeriesEpoch(t - 300_000, t - 100_000, true, 0)), tail)
    }

    @Test
    fun `no history at all is all non-use`() {
        assertEquals(emptyList<SeriesEpoch>(), buildTail(emptyList(), null, false, ObservationSnapshot.NONE, t))
    }

    @Test
    fun `observations at or after t are ignored`() {
        val raw = listOf(off(t - 600_000))
        val obs = watched(t - 900_000, ScreenObservation(t, true))

        assertEquals(emptyList<SeriesEpoch>(), buildTail(raw, t - 600_000, false, obs, t))
    }
}
