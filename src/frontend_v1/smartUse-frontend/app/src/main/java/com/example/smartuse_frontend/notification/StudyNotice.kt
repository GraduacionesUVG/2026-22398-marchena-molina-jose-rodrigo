package com.example.smartuse_frontend.notification

import com.example.smartuse_frontend.data.KeyPermission
import com.example.smartuse_frontend.study.END_OF_COLLECTION_TEXT
import com.example.smartuse_frontend.study.cutoffAnnouncement
import com.example.smartuse_frontend.ui.status.lapseSummary
import java.time.LocalDate

/**
 * Everything the app's one (silent) notification can say.
 */
sealed interface StudyNotice {
    /** Recording is active; [missing] is empty when every key permission is granted; [cutoff] is announced when set. */
    data class Recording(val missing: Set<KeyPermission>, val cutoff: LocalDate? = null) : StudyNotice

    data class HardOptOut(val stage: HardOptOutStage) : StudyNotice

    /** Day 21 or the backend's end of study: final, never re-posted. */
    object EndOfCollection : StudyNotice
}

enum class HardOptOutStage { PENDING, FAILED, DONE }

/** [openStatus]: the tap opens Status (to fix permissions) rather than the app's home. */
data class NoticeContent(val text: String, val ongoing: Boolean, val openStatus: Boolean)

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

    is StudyNotice.HardOptOut -> when (notice.stage) {
        HardOptOutStage.PENDING ->
            NoticeContent("Eliminando tus datos…", ongoing = true, openStatus = false)
        HardOptOutStage.FAILED -> NoticeContent(
            "Aún no se pudieron eliminar tus datos. Reintentando automáticamente.",
            ongoing = true,
            openStatus = false,
        )
        HardOptOutStage.DONE -> NoticeContent(
            "Tus datos fueron eliminados del servidor. Ya puedes desinstalar la aplicación.",
            ongoing = false,
            openStatus = false,
        )
    }

    StudyNotice.EndOfCollection -> NoticeContent(END_OF_COLLECTION_TEXT, ongoing = false, openStatus = false)
}

// Missing notifications has no effect line: if it's missing, nothing is shown anyway.
private fun lapseEffect(permission: KeyPermission): String? = when (permission) {
    KeyPermission.USAGE_ACCESS -> "No se puede leer el uso del teléfono hasta que vuelvas a otorgarlo."
    KeyPermission.BATTERY_EXEMPTION -> "El registro puede retrasarse."
    KeyPermission.NOTIFICATIONS -> null
}
