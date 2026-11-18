package com.example.smartuse_frontend.notification

import com.example.smartuse_frontend.data.KeyPermission
import com.example.smartuse_frontend.study.END_OF_COLLECTION_TEXT
import com.example.smartuse_frontend.study.OPTED_OUT_TEXT
import com.example.smartuse_frontend.study.cutoffAnnouncement
import com.example.smartuse_frontend.ui.status.lapseSummary
import java.time.LocalDate

/**
 * Everything the app's one (silent) notification can say.
 */
sealed interface StudyNotice {
    /** Recording is active; [missing] is empty when every key permission is granted; [cutoff] is announced when set. */
    data class Recording(val missing: Set<KeyPermission>, val cutoff: LocalDate? = null) : StudyNotice

    /** The study ended and the end forms are pending (ENDED); the tap opens them. */
    object FormsPending : StudyNotice

    /** The forms are submitted and the final send is pending (FORMS_DONE). */
    object Sending : StudyNotice

    /** The final send is confirmed: the end notice, never re-posted. */
    data class Finished(val optedOut: Boolean) : StudyNotice
}

/** The recording notification; also the foreground service's (StudyService). */
const val STUDY_NOTIFICATION_ID = 3001

/**
 * The end notice. Its own id: stopping the service removes 3001 when the
 * service is destroyed, which can happen after the end notice is posted.
 */
const val END_NOTIFICATION_ID = 3002

fun notificationIdFor(notice: StudyNotice): Int = when (notice) {
    is StudyNotice.Recording, StudyNotice.FormsPending, StudyNotice.Sending -> STUDY_NOTIFICATION_ID
    is StudyNotice.Finished -> END_NOTIFICATION_ID
}

/**
 * [openStatus]: the tap opens Status (to fix permissions) rather than the
 * app's home; [openEndForms]: the tap asks for the end forms.
 */
data class NoticeContent(
    val text: String,
    val ongoing: Boolean,
    val openStatus: Boolean,
    val title: String = "SmartUse",
    val openEndForms: Boolean = false,
)

private const val RECORDING_TEXT = "SmartUse está registrando el uso de tu teléfono para el estudio."

fun studyNoticeContent(notice: StudyNotice): NoticeContent = when (notice) {
    is StudyNotice.Recording -> {
        val base = if (notice.missing.isEmpty()) {
            NoticeContent(RECORDING_TEXT, ongoing = true, openStatus = false)
        } else {
            val effects = KeyPermission.entries.filter { it in notice.missing }.mapNotNull(::lapseEffect)
            NoticeContent(
                text = (listOf(lapseSummary(notice.missing)) + effects).joinToString(" "),
                ongoing = true,
                openStatus = true,
            )
        }
        if (notice.cutoff == null) base else base.copy(text = "${base.text} ${cutoffAnnouncement(notice.cutoff)}")
    }

    StudyNotice.FormsPending -> NoticeContent(
        "Es el último paso del estudio. Toca para empezar.",
        ongoing = true,
        openStatus = false,
        title = "Completa los formularios finales",
        openEndForms = true,
    )

    StudyNotice.Sending -> NoticeContent(
        "Se enviarán cuando haya conexión.",
        ongoing = true,
        openStatus = false,
        title = "Enviando tus respuestas…",
    )

    is StudyNotice.Finished -> NoticeContent(
        if (notice.optedOut) OPTED_OUT_TEXT else END_OF_COLLECTION_TEXT,
        ongoing = false,
        openStatus = false,
    )
}

// Missing notifications has no effect line: if it's missing, nothing is shown anyway.
private fun lapseEffect(permission: KeyPermission): String? = when (permission) {
    KeyPermission.USAGE_ACCESS -> "No se puede leer el uso del teléfono hasta que vuelvas a otorgarlo."
    KeyPermission.BATTERY_EXEMPTION -> "El registro puede retrasarse."
    KeyPermission.NOTIFICATIONS -> null
    KeyPermission.OVERLAY -> "No se pueden mostrar las intervenciones del estudio."
}
