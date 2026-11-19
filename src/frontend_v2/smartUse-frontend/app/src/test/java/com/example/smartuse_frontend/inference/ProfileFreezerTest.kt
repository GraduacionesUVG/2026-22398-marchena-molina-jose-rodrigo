package com.example.smartuse_frontend.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFreezerTest {

    private class FakeProfileStore : ProfileStore {
        val profiles = mutableMapOf<Pattern, Profile>()
        var freezes = 0
        override fun read(pattern: Pattern): Profile? = profiles[pattern]
        override fun freeze(f: Profile, s: Profile) {
            check(profiles.isEmpty()) { "already frozen" }
            freezes++
            profiles[Pattern.F] = f
            profiles[Pattern.S] = s
        }
    }

    private val person = "u40466b"
    private val all = Fixtures.epochs(person)
    private val days = profileDays(profilePoints(all, buildSessions(all), Fixtures.OFFSET_S, 0), Fixtures.OFFSET_S)
    private val readyAt = profileReadyAt(days, Fixtures.OFFSET_S)
    private val store = FakeProfileStore()
    private val logs = mutableListOf<String>()

    /** The data as the device would hold it at [end]: epochs started before it, the last one being the dangling one. */
    private fun upTo(end: Double) = all.filter { it.start < end }

    private fun freezer(epochs: List<ModelEpoch>, forcedAt: Double? = null) =
        ProfileFreezer(store, { epochs }, { Fixtures.OFFSET_S }, forcedAt, { logs += it })

    private val never = Double.MAX_VALUE

    /** End of the first profile day (local): the data holds one usable day. */
    private val endOfDay0 = days[0] * 86_400.0 - Fixtures.OFFSET_S + 86_400.0

    @Test
    fun `freezes once the third day plus an hour is covered`() {
        val before = upTo(readyAt - 1)
        assertTrue("the data reaches past the third day", before.last().start > days.last() * 86_400.0)
        assertFalse(freezer(before).tryFreeze(never))
        assertNull(store.read(Pattern.F))

        val after = all.filter { it.start <= all.first { e -> e.start >= readyAt }.start }
        assertTrue(freezer(after).tryFreeze(never))
        assertEquals(1, store.freezes)
        val expected = buildProfile(Pattern.F, all, buildSessions(all), Fixtures.OFFSET_S)!!
        assertEquals(expected.pf, store.read(Pattern.F)!!.pf, 1e-9)
        assertEquals(expected.profileDays, store.read(Pattern.S)!!.profileDays)
        assertTrue(logs.any { it.startsWith("profile: frozen") })
    }

    @Test
    fun `already frozen does nothing`() {
        assertTrue(freezer(all).tryFreeze(never))
        assertTrue(freezer(all).tryFreeze(never))
        assertEquals(1, store.freezes)
    }

    @Test
    fun `fewer than 3 days does nothing`() {
        assertFalse(freezer(upTo(days[1] * 86_400.0 - Fixtures.OFFSET_S + 86_400.0)).tryFreeze(never))
        assertEquals(0, store.freezes)
        assertTrue(logs.any { it.startsWith("profile: 2 of 3 days") })
    }

    @Test
    fun `days so far for the debug card`() {
        assertEquals(0, freezer(emptyList()).daysSoFar())
        assertEquals(2, freezer(upTo(days[1] * 86_400.0 - Fixtures.OFFSET_S + 86_400.0)).daysSoFar())
        assertTrue(freezer(all).tryFreeze(never))
        assertTrue(freezer(emptyList()).isFrozen())
    }

    @Test
    fun `no data does nothing`() {
        assertFalse(freezer(emptyList()).tryFreeze(never))
        assertEquals(0, store.freezes)
    }

    @Test
    fun `before forcedAt one usable day does not freeze`() {
        assertFalse(freezer(upTo(endOfDay0), forcedAt = endOfDay0 + 1).tryFreeze(endOfDay0))
        assertEquals(0, store.freezes)
    }

    @Test
    fun `at forcedAt one usable day freezes from it`() {
        assertTrue(freezer(upTo(endOfDay0), forcedAt = endOfDay0).tryFreeze(endOfDay0))
        assertEquals(1, store.freezes)
        assertEquals(1, store.read(Pattern.F)!!.profileDays.size)
        assertEquals(store.read(Pattern.F)!!.profileDays, store.read(Pattern.S)!!.profileDays)
        assertTrue(logs.contains("profile: frozen (forced, 1 days)"))
    }

    /** One local day (offset 0) with sessions in only 4 clock hours: never a usable day. */
    private val shortDay = buildList {
        for (h in 0 until 4) {
            add(ModelEpoch(h * 3600.0, h * 3600.0 + 60, true, 1))
            add(ModelEpoch(h * 3600.0 + 60, (h + 1) * 3600.0, false, 0))
        }
        add(ModelEpoch(4 * 3600.0, 5 * 3600.0, false, 0))
        add(ModelEpoch(5 * 3600.0, 5 * 3600.0, false, 0, open = true))
    }

    private fun zeroOffsetFreezer(epochs: List<ModelEpoch>) = ProfileFreezer(store, { epochs }, { 0.0 }, 0.0, { logs += it })

    @Test
    fun `forced with only unusable days freezes from those`() {
        assertFalse(ProfileFreezer(store, { shortDay }, { 0.0 }, null, { logs += it }).tryFreeze(never))
        assertTrue(zeroOffsetFreezer(shortDay).tryFreeze(5 * 3600.0))
        assertEquals(listOf("1970-01-01"), store.read(Pattern.F)!!.profileDays)
        assertEquals(listOf("1970-01-01"), store.read(Pattern.S)!!.profileDays)
    }

    @Test
    fun `forced never freezes one profile alone`() {
        // One session at the data start: S's window starts before the data (censored) while F's doesn't.
        val one = listOf(
            ModelEpoch(0.0, 60.0, true, 1),
            ModelEpoch(60.0, 7200.0, false, 0),
            ModelEpoch(7200.0, 7200.0, false, 0, open = true),
        )
        assertTrue(zeroOffsetFreezer(one).tryFreeze(7200.0))
        assertEquals(store.read(Pattern.F)!!.profileDays, store.read(Pattern.S)!!.profileDays)
    }

    @Test
    fun `forced with no data does not freeze, then freezes on the first data`() {
        assertFalse(zeroOffsetFreezer(emptyList()).tryFreeze(1.0))
        assertEquals(0, store.freezes)
        assertTrue(zeroOffsetFreezer(shortDay).tryFreeze(5 * 3600.0))
    }

    @Test
    fun `forced never uses a day still in progress`() {
        // 3 usable days, the third not over (the normal rule waits for readyAt): forced takes the 2 complete ones.
        val waiting = upTo(readyAt - 1)
        assertTrue(freezer(waiting, forcedAt = 0.0).tryFreeze(readyAt - 1))
        assertEquals(days.take(2).map { java.time.LocalDate.ofEpochDay(it).toString() }, store.read(Pattern.F)!!.profileDays)
    }
}
