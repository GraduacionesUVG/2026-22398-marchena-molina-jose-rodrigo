package com.example.smartuse_frontend.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineTest {

    private val t = 10_000_000L

    @Test
    fun `canonical epochs then tail use epochs with non-use gaps`() {
        // Canonical: on 1 000 000 (app a), off 2 000 000; dangling on at 3 000 000.
        // The service saw the screen go off at 4 000 000 and on again at 9 000 000.
        val raw = RawLog().on(1_000_000).app(1_500_000, "a").off(2_000_000).on(3_000_000).app(3_500_000, "b").rows
        val obs = ObservationSnapshot(
            observingSince = 500_000,
            observations = listOf(ScreenObservation(4_000_000, false), ScreenObservation(9_000_000, true)),
        )

        val epochs = timelineUpTo(FakeUsageSource(raw), obs, t)

        assertEquals(
            listOf(
                ModelEpoch(1_000.0, 2_000.0, true, 1),
                ModelEpoch(2_000.0, 3_000.0, false, 0),
                ModelEpoch(3_000.0, 4_000.0, true, 1),
                ModelEpoch(4_000.0, 9_000.0, false, 0),
                ModelEpoch(9_000.0, 10_000.0, true, 0, open = true),
            ),
            epochs,
        )
    }

    @Test
    fun `screen on at t gives an open last epoch ending at t`() {
        val raw = RawLog().on(1_000_000).app(1_100_000, "a").off(2_000_000).on(9_500_000).rows
        val obs = ObservationSnapshot(500_000, emptyList())

        val last = timelineUpTo(FakeUsageSource(raw), obs, t).last()

        assertEquals(ModelEpoch(9_500.0, 10_000.0, true, 0, open = true), last)
    }

    @Test
    fun `screen off at t leaves no open epoch`() {
        val raw = RawLog().on(1_000_000).app(1_100_000, "a").off(2_000_000).rows
        val obs = ObservationSnapshot(500_000, emptyList())

        val epochs = timelineUpTo(FakeUsageSource(raw), obs, t)

        assertEquals(listOf(ModelEpoch(1_000.0, 2_000.0, true, 1), ModelEpoch(2_000.0, 10_000.0, false, 0)), epochs)
    }

    @Test
    fun `a screen-on exactly at t gives a zero-length open epoch`() {
        // The screen_on decision point: t is the service's screen-on time, USM hasn't published it.
        val raw = RawLog().on(1_000_000).app(1_100_000, "a").off(2_000_000).rows
        val obs = ObservationSnapshot(500_000, listOf(ScreenObservation(t, true)))

        val epochs = timelineUpTo(FakeUsageSource(raw), obs, t)

        assertEquals(
            listOf(
                ModelEpoch(1_000.0, 2_000.0, true, 1),
                ModelEpoch(2_000.0, 10_000.0, false, 0),
                ModelEpoch(10_000.0, 10_000.0, true, 0, open = true),
            ),
            epochs,
        )
        assertEquals(2, buildSessions(epochs).size)
    }

    @Test
    fun `USM's own record of the screen-on just before t is the same session starting at t`() {
        // USM stamped the screen-on 300 ms before the service's broadcast time t, and capture already has it.
        val raw = RawLog().on(1_000_000).app(1_100_000, "a").off(2_000_000).on(t - 300).rows
        val obs = ObservationSnapshot(500_000, listOf(ScreenObservation(t, true)))

        val epochs = timelineUpTo(FakeUsageSource(raw), obs, t)

        assertEquals(
            listOf(
                ModelEpoch(1_000.0, 2_000.0, true, 1),
                ModelEpoch(2_000.0, (t - 300) / 1000.0, false, 0),
                ModelEpoch((t - 300) / 1000.0, 10_000.0, false, 0),
                ModelEpoch(10_000.0, 10_000.0, true, 0, open = true),
            ),
            epochs,
        )
        // §11.11: the new session is not in the day block.
        val sessions = buildSessions(epochs)
        assertEquals(10_000.0, sessions.last().start, 0.0)
        val profile = kotlinx.serialization.json.Json.decodeFromString(
            Profile.serializer(), Fixtures.profileJson("u40466b", Pattern.S).toString(),
        )
        val x = extract(Pattern.S, sessions, 10_000.0, profile, 0.0)
        assertEquals(1f, x[S_FEATURES.indexOf("freq_day")])
        assertEquals(0f, x[S_FEATURES.indexOf("cur_logdur")])
    }

    @Test
    fun `nothing captured yet gives the observed tail only`() {
        val obs = ObservationSnapshot(9_000_000, listOf(ScreenObservation(9_000_000, true)))

        assertEquals(listOf(ModelEpoch(9_000.0, 10_000.0, true, 0, open = true)), timelineUpTo(FakeUsageSource(emptyList()), obs, t))
    }

    @Test
    fun `captured epochs end with the dangling one, where the data ends`() {
        val raw = RawLog().on(1_000_000).app(1_500_000, "a").off(2_000_000).on(3_000_000).rows

        assertEquals(
            listOf(
                ModelEpoch(1_000.0, 2_000.0, true, 1),
                ModelEpoch(2_000.0, 3_000.0, false, 0),
                ModelEpoch(3_000.0, 3_000.0, false, 0),
            ),
            capturedEpochs(FakeUsageSource(raw)),
        )
        assertEquals(emptyList<ModelEpoch>(), capturedEpochs(FakeUsageSource(emptyList())))
    }

    @Test
    fun `ms to seconds`() {
        val raw = RawLog().on(1_234).app(1_500, "a").off(2_345).on(3_000).rows
        val epochs = timelineUpTo(FakeUsageSource(raw), ObservationSnapshot(0, emptyList()), 4_000)
        assertEquals(1.234, epochs.first().start, 0.0)
        assertEquals(2.345, epochs.first().end, 0.0)
    }
}
