package com.example.smartuse_frontend.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenEventTest {
    @Test
    fun `screen-on, unlock and screen-off map to their events`() {
        assertEquals(ScreenEvent.SCREEN_ON, screenEventFor("android.intent.action.SCREEN_ON"))
        assertEquals(ScreenEvent.USER_PRESENT, screenEventFor("android.intent.action.USER_PRESENT"))
        assertEquals(ScreenEvent.SCREEN_OFF, screenEventFor("android.intent.action.SCREEN_OFF"))
    }

    @Test
    fun `anything else is ignored`() {
        assertNull(screenEventFor("android.intent.action.BATTERY_LOW"))
        assertNull(screenEventFor(null))
    }

    @Test
    fun `only screen-on and screen-off change the screen state`() {
        assertEquals(true, screenStateFor(ScreenEvent.SCREEN_ON))
        assertEquals(false, screenStateFor(ScreenEvent.SCREEN_OFF))
        assertNull(screenStateFor(ScreenEvent.USER_PRESENT))
    }
}
