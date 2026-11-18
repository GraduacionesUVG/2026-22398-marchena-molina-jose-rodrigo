package com.example.smartuse_frontend.nav

import com.example.smartuse_frontend.study.EndingState
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRouteTest {

    @Test
    fun `waits for the ending state, and for setup while live`() {
        assertEquals(AppRoute.LOADING, appRoute(setupComplete = true, endingState = null))
        assertEquals(AppRoute.LOADING, appRoute(setupComplete = null, endingState = EndingState.LIVE))
    }

    @Test
    fun `a live study goes to setup or the dashboard`() {
        assertEquals(AppRoute.SETUP, appRoute(setupComplete = false, endingState = EndingState.LIVE))
        assertEquals(AppRoute.APP, appRoute(setupComplete = true, endingState = EndingState.LIVE))
    }

    @Test
    fun `an ended study shows the forms, then the end screen, whatever setup says`() {
        for (setup in listOf(null, false, true)) {
            assertEquals(AppRoute.END_FORMS, appRoute(setup, EndingState.ENDED))
            assertEquals(AppRoute.ENDED, appRoute(setup, EndingState.FORMS_DONE))
            assertEquals(AppRoute.ENDED, appRoute(setup, EndingState.SENT))
        }
    }
}