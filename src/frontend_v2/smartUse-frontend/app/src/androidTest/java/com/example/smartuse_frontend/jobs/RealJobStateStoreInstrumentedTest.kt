package com.example.smartuse_frontend.jobs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.network.FailureKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.example.smartuse_frontend.inference.FiringState
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RealJobStateStoreInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = RealJobStateStore(context)

    @Before
    fun reset() = runBlocking {
        JobName.values().forEach { store.put(it, JobState()) }
    }

    @Test
    fun stateRoundTripsThroughDataStore() = runBlocking {
        val t = Instant.parse("2026-09-20T10:00:00Z")
        val state = JobState().afterFailedRun(t, JobFailure(FailureKind.PERMANENT, "boom"), 20)

        store.put(JobName.CAPTURE, state)

        assertEquals(state, store.get(JobName.CAPTURE))
    }

    @Test
    fun clearingFieldsRemovesThem() = runBlocking {
        val t = Instant.parse("2026-09-20T10:00:00Z")
        store.put(JobName.CAPTURE, JobState().afterFailedRun(t, JobFailure(FailureKind.TRANSIENT, "x"), 10))

        store.put(JobName.CAPTURE, JobState().afterSuccess(t, 10))

        assertNull(store.get(JobName.CAPTURE).lastFailure)
        assertNull(store.get(JobName.CAPTURE).firstFailureAt)
    }

    @Test
    fun optOutFlagPersists() = runBlocking {
        val t = Instant.parse("2026-09-21T09:00:00Z")
        store.setOptedOutAt(t)

        assertEquals(t, store.optedOutAt())
    }

    @Test
    fun endingMarksPersist() = runBlocking {
        val t = Instant.parse("2026-12-01T09:00:00Z")
        store.setFormsCompletedAt(t)
        store.setFinalSentAt(t.plusSeconds(60))
        store.setEndCleanupDoneAt(t.plusSeconds(120))

        val marks = store.readEndMarks()
        assertEquals(t, marks.formsCompletedAt)
        assertEquals(t.plusSeconds(60), marks.finalSentAt)
        assertEquals(t.plusSeconds(120), store.endCleanupDoneAt())
    }

    @Test
    fun firingStateRoundTrips() = runBlocking {
        val state = FiringState(
            lastShownF = Instant.parse("2026-10-20T16:00:00Z"),
            lastShownS = null,
            shownDay = LocalDate.of(2026, 10, 20),
            shownToday = 3,
        )
        store.setFiringState(state)
        assertEquals(state, store.firingState())
        store.setFiringState(FiringState())
        assertEquals(FiringState(), store.firingState())
    }
}
