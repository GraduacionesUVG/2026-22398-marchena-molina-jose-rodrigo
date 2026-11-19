package com.example.smartuse_frontend.e2e

import com.example.smartuse_frontend.intervention.OVERLAY_PANEL_TITLE
import com.example.smartuse_frontend.intervention.OVERLAY_TINT_TITLE
import com.example.smartuse_frontend.intervention.REMINDER_CARD_TITLE

/** The intervention's two windows as the window manager sees them. */
object OverlayOnScreen {
    private fun present(): Set<String> {
        val windows = DeviceShell.run("dumpsys window windows")
        return setOf(OVERLAY_TINT_TITLE, OVERLAY_PANEL_TITLE).filter { windows.contains(it) }.toSet()
    }

    fun isUp(): Boolean = present().size == 2
    fun isGone(): Boolean = present().isEmpty()

    /** The end-forms reminder card (ReminderCard), a window of its own. */
    fun isReminderCardUp(): Boolean = DeviceShell.run("dumpsys window windows").contains(REMINDER_CARD_TITLE)
}
