package com.example.smartuse_frontend.service

import com.example.smartuse_frontend.study.EndingState
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceModeTest {

    @Test
    fun `the service runs from setup until the confirmed send`() {
        assertEquals(ServiceMode.STUDY, ServiceMode.of(EndingState.LIVE, isStudyLive = true))
        assertEquals(ServiceMode.FORMS_REMINDER, ServiceMode.of(EndingState.ENDED, isStudyLive = false))
        assertEquals(ServiceMode.SENDING, ServiceMode.of(EndingState.FORMS_DONE, isStudyLive = false))
        assertEquals(ServiceMode.OFF, ServiceMode.of(EndingState.SENT, isStudyLive = false))
    }

    @Test
    fun `live but not running - before setup or past the window - is off`() {
        assertEquals(ServiceMode.OFF, ServiceMode.of(EndingState.LIVE, isStudyLive = false))
    }

    @Test
    fun `the card shows at unlock only while the forms are pending and it may be drawn`() {
        assertEquals(CardAction.SHOW, reminderCardAction(ServiceMode.FORMS_REMINDER, ScreenEvent.USER_PRESENT, canDrawOverlays = true))
        assertEquals(CardAction.NONE, reminderCardAction(ServiceMode.FORMS_REMINDER, ScreenEvent.USER_PRESENT, canDrawOverlays = false))
        assertEquals(CardAction.NONE, reminderCardAction(ServiceMode.FORMS_REMINDER, ScreenEvent.SCREEN_ON, canDrawOverlays = true))
        for (mode in listOf(ServiceMode.STUDY, ServiceMode.SENDING, ServiceMode.OFF)) {
            assertEquals(CardAction.NONE, reminderCardAction(mode, ScreenEvent.USER_PRESENT, canDrawOverlays = true))
        }
    }

    @Test
    fun `screen-off always removes the card`() {
        for (mode in ServiceMode.entries) {
            assertEquals(CardAction.REMOVE, reminderCardAction(mode, ScreenEvent.SCREEN_OFF, canDrawOverlays = true))
        }
    }
}