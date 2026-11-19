package com.example.smartuse_frontend.ui.welcome

import com.example.smartuse_frontend.inference.ProfileFreezer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val QUARTER_S = Duration.ofMinutes(15).seconds

/** The dashboard's interventions box: the latest start while the profiles aren't frozen, then "active". */
sealed interface InterventionsBox {
    data class StartsBy(val at: Instant) : InterventionsBox
    object Active : InterventionsBox
}

const val INTERVENTIONS_ACTIVE = "Intervenciones visuales activas"

/**
 * The latest moment interventions can start: the forced freeze (signup + 72 h)
 * plus one cycle `t` to run it, rounded up to the quarter hour; a promise the
 * forced freeze lets the app keep.
 */
fun interventionsStartAt(signup: Instant, cycleMinutes: Long): Instant {
    val s = signup.plus(ProfileFreezer.FORCED_AFTER).plus(Duration.ofMinutes(cycleMinutes)).epochSecond
    return Instant.ofEpochSecond(Math.floorDiv(s + QUARTER_S - 1, QUARTER_S) * QUARTER_S)
}

private val SPANISH = Locale.forLanguageTag("es")
private val DAY = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SPANISH)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", SPANISH)

/** The dashboard's start box (placeholder wording, for the researcher). */
fun interventionsStartLine(at: Instant, zone: ZoneId): String {
    val local = at.atZone(zone)
    return "Las intervenciones visuales comenzarán a más tardar el ${DAY.format(local)} a las ${TIME.format(local)}."
}
