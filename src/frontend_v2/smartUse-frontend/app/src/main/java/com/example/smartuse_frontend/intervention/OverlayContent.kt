package com.example.smartuse_frontend.intervention

import com.example.smartuse_frontend.inference.Pattern
import com.example.smartuse_frontend.inference.Scores
import java.time.Duration

/** Window alpha of the gray tint (tuned on device). */
const val OVERLAY_TINT_ALPHA = 0.5f

/**
 * From Android 12 a touch passes through another app's non-touchable window
 * only if that window's alpha is at most this (InputManager's default maximum
 * obscuring opacity). Above it the tint would swallow every touch.
 */
const val MAX_TOUCH_THROUGH_ALPHA = 0.8f

/** The countdown, which is also the auto-dismiss. */
val OVERLAY_DURATION: Duration = Duration.ofSeconds(10)

/** Window titles, so the instrumented tests can find the windows in `dumpsys window`. */
const val OVERLAY_TINT_TITLE = "SmartUseInterventionTint"
const val OVERLAY_PANEL_TITLE = "SmartUseInterventionPanel"

internal const val OVERLAY_TITLE = "Intervención visual de SmartUse para ayudarte a notar tu uso del teléfono"

/** The close button's accessibility label (the button itself is an X). */
internal const val OVERLAY_CLOSE = "Cerrar"

/** The preview's footer (Status page button); placeholder wording. */
internal const val PREVIEW_FOOTER = "Intervención de prueba: no cuenta para el estudio."

/**
 * What the overlay's footer says: the pattern shown, or both when both models fired.
 * PREVIEW is the participant's test from Status, never a decision ([overlayCue] never returns it).
 */
enum class OverlayCue { F, S, BOTH, PREVIEW }

/**
 * BOTH when both scores are above their cutoffs (a model "fires", §9.3 rule 1),
 * whichever pattern the rules chose; otherwise the chosen one. Text only: the
 * firing state still counts the chosen pattern alone.
 */
fun overlayCue(chosen: Pattern, scores: Scores): OverlayCue = when {
    scores.f > scores.fCutoff && scores.s > scores.sCutoff -> OverlayCue.BOTH
    chosen == Pattern.F -> OverlayCue.F
    else -> OverlayCue.S
}

fun footerText(cue: OverlayCue): String = when (cue) {
    OverlayCue.F -> "Posibles revisiones frecuentes del teléfono en la próxima hora"
    OverlayCue.S -> "Este periodo de uso podría estar alargándose sin pausa"
    OverlayCue.BOTH -> "Posibles revisiones frecuentes o uso prolongado en la siguiente hora"
    OverlayCue.PREVIEW -> PREVIEW_FOOTER
}

fun countdownText(secondsLeft: Long): String =
    "Esta pestaña se cerrará en $secondsLeft ${if (secondsLeft == 1L) "segundo" else "segundos"}"
