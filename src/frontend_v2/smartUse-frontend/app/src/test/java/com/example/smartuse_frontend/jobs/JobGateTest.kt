package com.example.smartuse_frontend.jobs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class JobGateTest {

    private fun gate(
        hasSession: Boolean = true,
        windowOpen: Boolean = true,
        optedOut: Boolean = false,
        ended: Boolean = false,
    ) = JobGate(
        hasSession = { hasSession },
        isWindowOpen = { windowOpen },
        isOptedOut = { optedOut },
        isCollectionEnded = { ended },
    )

    @Test
    fun `open when there is a session, the window is open, nobody opted out and nothing ended`() = runBlocking {
        assertEquals(GateResult.OPEN, gate().check())
    }

    @Test
    fun `no session means setup is not finished`() = runBlocking {
        assertEquals(GateResult.NO_SESSION, gate(hasSession = false).check())
    }

    @Test
    fun `a closed window is reported`() = runBlocking {
        assertEquals(GateResult.WINDOW_CLOSED, gate(windowOpen = false).check())
    }

    @Test
    fun `opt-out is reported`() = runBlocking {
        assertEquals(GateResult.OPTED_OUT, gate(optedOut = true).check())
    }

    @Test
    fun `an ended collection is reported`() = runBlocking {
        assertEquals(GateResult.COLLECTION_ENDED, gate(ended = true).check())
    }

    @Test
    fun `opt-out wins over everything, even after the erase removed the session`() = runBlocking {
        assertEquals(GateResult.OPTED_OUT, gate(hasSession = false, windowOpen = false, optedOut = true, ended = true).check())
    }

    @Test
    fun `an ended collection wins over the missing session the erase leaves behind`() = runBlocking {
        assertEquals(GateResult.COLLECTION_ENDED, gate(hasSession = false, windowOpen = false, ended = true).check())
    }

    @Test
    fun `a missing session wins over the window`() = runBlocking {
        assertEquals(GateResult.NO_SESSION, gate(hasSession = false, windowOpen = false).check())
    }

    @Test
    fun `the study is live only when the gate is open`() = runBlocking {
        assertEquals(true, gate().isStudyLive())
        assertEquals(false, gate(hasSession = false).isStudyLive())
        assertEquals(false, gate(windowOpen = false).isStudyLive())
        assertEquals(false, gate(optedOut = true).isStudyLive())
        assertEquals(false, gate(ended = true).isStudyLive())
    }
}
