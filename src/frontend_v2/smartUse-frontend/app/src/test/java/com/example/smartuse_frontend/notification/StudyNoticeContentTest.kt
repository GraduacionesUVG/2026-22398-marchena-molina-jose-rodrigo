package com.example.smartuse_frontend.notification

import com.example.smartuse_frontend.data.KeyPermission
import com.example.smartuse_frontend.study.OPTED_OUT_TEXT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StudyNoticeContentTest {

    @Test
    fun `recording with every permission is the plain ongoing line that opens home`() {
        assertEquals(
            NoticeContent(
                "SmartUse está registrando el uso de tu teléfono para el estudio.",
                ongoing = true,
                openStatus = false,
            ),
            studyNoticeContent(StudyNotice.Recording(emptySet())),
        )
    }

    @Test
    fun `a lapse lists the missing permissions and their effect, and opens Status`() {
        val content = studyNoticeContent(
            StudyNotice.Recording(setOf(KeyPermission.USAGE_ACCESS, KeyPermission.BATTERY_EXEMPTION)),
        )

        assertEquals(
            "Los permisos de acceso al uso y ejecución en segundo plano han sido eliminados. " +
                "No se puede leer el uso del teléfono hasta que vuelvas a otorgarlo. " +
                "El registro puede retrasarse.",
            content.text,
        )
        assertEquals(true, content.ongoing)
        assertEquals(true, content.openStatus)
    }

    @Test
    fun `a missing overlay permission says interventions can't be shown`() {
        val content = studyNoticeContent(StudyNotice.Recording(setOf(KeyPermission.OVERLAY)))

        assertEquals(
            "El permiso de mostrar sobre otras apps ha sido eliminado. " +
                "No se pueden mostrar las intervenciones del estudio.",
            content.text,
        )
        assertTrue(content.openStatus)
    }

    @Test
    fun `missing notifications has a summary but no effect line`() {
        assertEquals(
            "El permiso de notificaciones ha sido eliminado.",
            studyNoticeContent(StudyNotice.Recording(setOf(KeyPermission.NOTIFICATIONS))).text,
        )
    }

    @Test
    fun `the forms reminder has its own title and opens the end forms`() {
        assertEquals(
            NoticeContent(
                "Es el último paso del estudio. Toca para empezar.",
                ongoing = true,
                openStatus = false,
                title = "Completa los formularios finales",
                openEndForms = true,
            ),
            studyNoticeContent(StudyNotice.FormsPending),
        )
    }

    @Test
    fun `sending says it waits for a connection`() {
        val content = studyNoticeContent(StudyNotice.Sending)

        assertEquals("Enviando tus respuestas…", content.title)
        assertEquals("Se enviarán cuando haya conexión.", content.text)
        assertEquals(true, content.ongoing)
        assertEquals(false, content.openEndForms)
    }

    @Test
    fun `the end notice says why the study ended and can be dismissed`() {
        assertEquals(
            NoticeContent(
                "El periodo de recolección terminó. Gracias por participar. Ya puedes desinstalar la aplicación.",
                ongoing = false,
                openStatus = false,
            ),
            studyNoticeContent(StudyNotice.Finished(optedOut = false)),
        )
        assertEquals(OPTED_OUT_TEXT, studyNoticeContent(StudyNotice.Finished(optedOut = true)).text)
        assertEquals(false, studyNoticeContent(StudyNotice.Finished(optedOut = true)).ongoing)
    }

    private val announcement =
        "El periodo de recolección acabará el 1 de diciembre de 2026. Luego de esa fecha " +
            "la aplicación dejará de recolectar información."

    @Test
    fun `recording with a cutoff appends the announcement and still opens home`() {
        assertEquals(
            NoticeContent(
                "SmartUse está registrando el uso de tu teléfono para el estudio. $announcement",
                ongoing = true,
                openStatus = false,
            ),
            studyNoticeContent(StudyNotice.Recording(emptySet(), LocalDate.of(2026, 12, 1))),
        )
    }

    @Test
    fun `a lapse with a cutoff keeps the lapse first and still opens Status`() {
        val content = studyNoticeContent(
            StudyNotice.Recording(setOf(KeyPermission.NOTIFICATIONS), LocalDate.of(2026, 12, 1)),
        )

        assertEquals("El permiso de notificaciones ha sido eliminado. $announcement", content.text)
        assertEquals(true, content.openStatus)
    }

    @Test
    fun `recording uses the service's id and the end notice its own, so the service's teardown can't remove it`() {
        assertEquals(STUDY_NOTIFICATION_ID, notificationIdFor(StudyNotice.Recording(emptySet())))
        assertEquals(STUDY_NOTIFICATION_ID, notificationIdFor(StudyNotice.FormsPending))
        assertEquals(STUDY_NOTIFICATION_ID, notificationIdFor(StudyNotice.Sending))
        assertEquals(END_NOTIFICATION_ID, notificationIdFor(StudyNotice.Finished(optedOut = false)))
        assertNotEquals(STUDY_NOTIFICATION_ID, END_NOTIFICATION_ID)
    }
}
