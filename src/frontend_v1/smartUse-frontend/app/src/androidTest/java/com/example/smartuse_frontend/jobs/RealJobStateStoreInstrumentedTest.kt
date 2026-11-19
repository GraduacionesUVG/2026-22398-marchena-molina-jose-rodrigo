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
import java.time.Instant

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

        store.put(JobName.SYNC, state)

        assertEquals(state, store.get(JobName.SYNC))
        assertEquals(JobState(), store.get(JobName.CAPTURE))
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

}
