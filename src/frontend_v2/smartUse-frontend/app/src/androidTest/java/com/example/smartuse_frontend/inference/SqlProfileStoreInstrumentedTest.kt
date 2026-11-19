package com.example.smartuse_frontend.inference

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.data.db.CanonicalDbHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlProfileStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "profile-store-test.db"
    private val store = SqlProfileStore(CanonicalDbHelper(context, name))

    private fun profile(pf: Double) = Profile(
        pf = pf, ps = 2.0, fQ = List(19) { it.toDouble() }, sQ = List(19) { it * 0.5 }, fMedian = 1.0, sMedian = 1.5,
        freqDay = 40.0, useDayMin = 200.0, logdurMedian = 4.0, nonuseDayMin = 700.0, lognonuseMedian = 6.0,
        sessionsByHour = List(24) { 1.0 }, profileDays = listOf("2026-10-01", "2026-10-02", "2026-10-03"),
        offsetS = -21_600.0, nProfilePoints = 150,
    )

    @After fun tearDown() { context.deleteDatabase(name) }

    @Test
    fun freezeThenReadBoth() {
        assertNull(store.read(Pattern.F))
        store.freeze(profile(1.0), profile(2.0))
        assertEquals(profile(1.0), store.read(Pattern.F))
        assertEquals(profile(2.0), store.read(Pattern.S))
    }

    @Test
    fun secondFreezeThrowsAndKeepsTheFirst() {
        store.freeze(profile(1.0), profile(2.0))
        assertThrows(SQLiteConstraintException::class.java) { store.freeze(profile(9.0), profile(9.0)) }
        assertEquals(profile(1.0), store.read(Pattern.F))
        assertEquals(profile(2.0), store.read(Pattern.S))
    }

    @Test
    fun deletingTheDatabaseErasesTheProfiles() {
        store.freeze(profile(1.0), profile(2.0))
        context.deleteDatabase(name)
        assertNull(SqlProfileStore(CanonicalDbHelper(context, name)).read(Pattern.F))
    }
}
