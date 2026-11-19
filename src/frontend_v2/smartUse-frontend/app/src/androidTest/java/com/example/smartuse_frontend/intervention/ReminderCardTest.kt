package com.example.smartuse_frontend.intervention

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.e2e.DeviceShell
import com.example.smartuse_frontend.e2e.OverlayOnScreen
import com.example.smartuse_frontend.e2e.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderCardTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var opened = 0

    private fun card(durationMs: Long = 30_000) = ReminderCard(context, openForms = { opened++ }, durationMs = durationMs)
    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Before fun setUp() {
        DeviceShell.wakeAndUnlock()
        DeviceShell.grantOverlay()
    }

    @Test
    fun laterRemovesTheCardWithoutOpeningTheForms() {
        val card = card()
        assertTrue(onMain { card.show() })
        await("the card up") { true.takeIf { OverlayOnScreen.isReminderCardUp() } }

        onMain { card.clickLater() }

        await("the card gone") { true.takeIf { !OverlayOnScreen.isReminderCardUp() } }
        assertEquals(0, opened)
        assertFalse(onMain { card.isShowing })
    }

    @Test
    fun nowRemovesTheCardAndOpensTheFormsOnce() {
        val card = card()
        onMain { card.show() }
        await("the card up") { true.takeIf { OverlayOnScreen.isReminderCardUp() } }

        onMain { card.clickNow() }
        onMain { card.clickNow() } // gone: a second tap reaches nothing

        await("the card gone") { true.takeIf { !OverlayOnScreen.isReminderCardUp() } }
        assertEquals(1, opened)
    }

    @Test
    fun theCardRemovesItselfAndASecondDismissIsHarmless() {
        val card = card(durationMs = 500)
        onMain { card.show() }

        await("the card gone by itself") { true.takeIf { !onMain { card.isShowing } && !OverlayOnScreen.isReminderCardUp() } }
        onMain { card.dismiss() }
        onMain { card.dismiss() }
        assertEquals(0, opened)
    }
}