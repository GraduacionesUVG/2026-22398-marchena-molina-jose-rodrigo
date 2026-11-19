package com.example.smartuse_frontend.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class AppRouteTest {

    @Test
    fun `waits until all three flags have loaded`() {
        assertEquals(AppRoute.LOADING, appRoute(setupComplete = null, hasOptedOut = false, collectionEnded = false))
        assertEquals(AppRoute.LOADING, appRoute(setupComplete = true, hasOptedOut = null, collectionEnded = false))
        assertEquals(AppRoute.LOADING, appRoute(setupComplete = true, hasOptedOut = false, collectionEnded = null))
    }

    @Test
    fun `a fresh install goes to setup`() {
        assertEquals(AppRoute.SETUP, appRoute(setupComplete = false, hasOptedOut = false, collectionEnded = false))
    }

    @Test
    fun `a set-up install goes to the app`() {
        assertEquals(AppRoute.APP, appRoute(setupComplete = true, hasOptedOut = false, collectionEnded = false))
    }

    // The erase clears setup-complete along with the session: a terminal state must never fall back to setup.
    @Test
    fun `an ended collection shows the end-of-study screen, even after the erase cleared setup`() {
        assertEquals(AppRoute.END_OF_STUDY, appRoute(setupComplete = true, hasOptedOut = false, collectionEnded = true))
        assertEquals(AppRoute.END_OF_STUDY, appRoute(setupComplete = false, hasOptedOut = false, collectionEnded = true))
    }

    @Test
    fun `an opt-out shows its screen, even after the wipe cleared setup`() {
        assertEquals(AppRoute.OPT_OUT_DONE, appRoute(setupComplete = true, hasOptedOut = true, collectionEnded = false))
        assertEquals(AppRoute.OPT_OUT_DONE, appRoute(setupComplete = false, hasOptedOut = true, collectionEnded = false))
    }

    @Test
    fun `opt-out wins over an ended collection`() {
        assertEquals(AppRoute.OPT_OUT_DONE, appRoute(setupComplete = false, hasOptedOut = true, collectionEnded = true))
    }
}
