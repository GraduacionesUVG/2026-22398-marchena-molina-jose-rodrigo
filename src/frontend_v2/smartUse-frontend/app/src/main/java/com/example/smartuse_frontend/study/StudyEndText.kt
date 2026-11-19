package com.example.smartuse_frontend.study

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shown by the end screen and the final notification when collection ends (day 21 or the cutoff). */
const val END_OF_COLLECTION_TEXT =
    "El periodo de recolección terminó. Gracias por participar. Ya puedes desinstalar la aplicación."

/** Shown by the end screen after an opt-out. */
const val OPTED_OUT_TEXT =
    "Te retiraste del estudio. Gracias por tu participación. Ya puedes desinstalar la aplicación."

/** The end screen while the final send is pending (FORMS_DONE). */
const val SENDING_TITLE = "Enviando tus respuestas…"
const val SENDING_TEXT =
    "Se enviarán automáticamente cuando haya conexión. No desinstales la app todavía."

/** The end screen once the final send is confirmed (SENT); the line below it says why the study ended. */
const val THANKS_TITLE = "¡Gracias por participar!"

private val SPANISH_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es"))

/** Shown on Welcome and appended to the recording notification while a cutoff is announced. */
fun cutoffAnnouncement(cutoff: LocalDate): String =
    "El periodo de recolección acabará el ${cutoff.format(SPANISH_DATE)}. Luego de esa fecha " +
        "la aplicación dejará de recolectar información."
