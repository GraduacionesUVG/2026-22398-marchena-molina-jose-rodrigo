package com.example.smartuse_frontend.data

import android.app.usage.UsageEvents
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventVocabularyTest {

    private val own = "com.example.smartuse_deployment"

    @Test
    fun `own package activity is not captured`() {
        assertFalse(isCaptured(UsageEvents.Event.MOVE_TO_FOREGROUND, own, own))
    }

    @Test
    fun `launcher activity is captured`() {
        assertTrue(isCaptured(UsageEvents.Event.MOVE_TO_FOREGROUND, "com.android.launcher3", own))
    }

    @Test
    fun `screen events are captured whatever the package`() {
        assertTrue(isCaptured(UsageEvents.Event.SCREEN_INTERACTIVE, "android", own))
        assertTrue(isCaptured(UsageEvents.Event.SCREEN_NON_INTERACTIVE, own, own))
    }

    @Test
    fun `other event types are not captured`() {
        assertFalse(isCaptured(UsageEvents.Event.ACTIVITY_PAUSED, "com.android.launcher3", own))
    }
}
