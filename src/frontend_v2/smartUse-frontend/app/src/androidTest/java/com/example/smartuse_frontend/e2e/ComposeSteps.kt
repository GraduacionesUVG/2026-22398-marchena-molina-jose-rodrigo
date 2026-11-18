package com.example.smartuse_frontend.e2e

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.smartuse_frontend.forms.FormDefinitions
import com.example.smartuse_frontend.ui.endforms.END_FORMS_CONFIRM
import com.example.smartuse_frontend.ui.endforms.END_FORMS_NEXT
import com.example.smartuse_frontend.ui.endforms.END_FORMS_SEND
import com.example.smartuse_frontend.ui.setup.EARLIER_USAGE_REJECT
import com.example.smartuse_frontend.ui.setup.EARLIER_USAGE_TITLE
import com.example.smartuse_frontend.ui.terms.TERMS_ACCEPTANCE

fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 15_000) =
    waitUntil(timeoutMs) { onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

fun ComposeTestRule.tapText(text: String) = onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()

/** For a button inside a scrolling page, possibly below the fold. */
fun ComposeTestRule.scrollToAndTapText(text: String) =
    onAllNodes(hasText(text) and hasClickAction()).onFirst().performScrollTo().performClick()

fun ComposeTestRule.count(text: String, substring: Boolean = false): Int =
    onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size

/** With every answer drafted (Seed.filledEndForms): through every section to the confirmed "Enviar". */
fun ComposeTestRule.submitDraftedEndForms() {
    val sections = FormDefinitions.USABILITY.sections
    sections.dropLast(1).forEach {
        waitForText(it.title)
        scrollToAndTapText(END_FORMS_NEXT)
    }
    waitForText(sections.last().title)
    scrollToAndTapText(END_FORMS_SEND)
    tapText(END_FORMS_CONFIRM)
}

/**
 * The setup wizard from the terms to the home screen, permissions already
 * granted by shell; [beforeScore] runs on the score field before [score] is typed.
 * The earlier-usage step shows only on a phone with usage in the 3 days before
 * today; there it taps [earlierUsage]. True if the step showed.
 */
fun ComposeTestRule.walkSetupWizard(
    score: Int = 37,
    earlierUsage: String = EARLIER_USAGE_REJECT,
    beforeScore: (ComposeTestRule) -> Unit = {},
): Boolean {
    waitForText("Acepto y continúo")
    onNode(hasText(TERMS_ACCEPTANCE)).performScrollTo()
    tapText("Acepto y continúo")

    waitForText("Datos del participante")
    onNode(hasSetTextAction() and hasText("Edad")).performTextInput("21")
    onNode(hasSetTextAction() and hasText("Género")).performTextInput("Femenino")
    onNode(hasSetTextAction() and hasText("Carrera universitaria")).performTextInput(Seed.QA_MAJOR)
    beforeScore(this)
    onNode(hasSetTextAction() and hasText("Puntaje SAS-SV")).performTextInput(score.toString())
    scrollToAndTapText("Continuar")

    waitForText("Permiso de acceso al uso"); tapText("Continuar")
    waitForText("Permiso de notificaciones"); tapText("Continuar")
    waitForText("Ejecución en segundo plano"); tapText("Continuar")
    waitForText("Mostrar sobre otras apps"); tapText("Continuar")
    // Listed brands (e.g. a Samsung on Test Lab) show the autostart step.
    runCatching { waitForText("Inicio automático", 3_000) }.onSuccess { tapText("Continuar") }
    val shown = runCatching { waitForText(EARLIER_USAGE_TITLE, 5_000) }.onSuccess { tapText(earlierUsage) }.isSuccess

    waitForText("Estado", 30_000) // the home screen's first tile
    return shown
}
