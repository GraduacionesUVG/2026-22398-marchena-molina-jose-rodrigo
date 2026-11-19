package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.jobs.FakeJobStateStore
import com.example.smartuse_frontend.notification.FakeStudyNotifier
import com.example.smartuse_frontend.study.EndReason
import com.example.smartuse_frontend.study.EndTrigger
import com.example.smartuse_frontend.study.EndingState
import com.example.smartuse_frontend.study.StudyEnder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RealOptOutRepositoryTest {

    private val now = Instant.parse("2026-10-10T12:00:00Z")

    @Test
    fun `opting out asks the ender for the opt-out ending`() = runTest {
        val triggers = mutableListOf<EndTrigger>()

        RealOptOutRepository(endStudy = { triggers += it }).optOut()

        assertEquals(listOf(EndTrigger.OPT_OUT), triggers)
    }

    @Test
    fun `through the real ender the opt-out leads to the end forms`() = runTest {
        val store = FakeJobStateStore()
        val ender = StudyEnder(
            stateStore = store,
            cancelAll = {},
            stopInterventions = {},
            startService = {},
            notifier = FakeStudyNotifier(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            logger = {},
        )

        RealOptOutRepository(endStudy = ender::end).optOut()

        assertEquals(EndingState.ENDED, store.readEndMarks().state)
        assertEquals(EndReason.OPT_OUT, store.readEndMarks().reason)
    }
}