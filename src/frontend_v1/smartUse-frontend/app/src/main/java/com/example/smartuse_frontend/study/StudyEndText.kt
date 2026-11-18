package com.example.smartuse_frontend.study

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shown by the end-of-study screen and the final notification. */
const val END_OF_COLLECTION_TEXT =
    "El periodo de recolección terminó. Gracias por participar. Ya puedes desinstalar la aplicación."

private val SPANISH_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es"))

/** Shown on Welcome and appended to the recording notification while a cutoff is announced. */
fun cutoffAnnouncement(cutoff: LocalDate): String =
    "El periodo de recolección acabará el ${cutoff.format(SPANISH_DATE)}. Luego de esa fecha no podrás " +
        "realizar el retiro total del estudio y la aplicación dejará de recolectar información."
