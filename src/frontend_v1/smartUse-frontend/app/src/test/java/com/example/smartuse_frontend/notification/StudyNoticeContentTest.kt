package com.example.smartuse_frontend.notification

import com.example.smartuse_frontend.data.KeyPermission
import org.junit.Assert.assertEquals
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
    fun `missing notifications has a summary but no effect line`() {
        assertEquals(
            "El permiso de notificaciones ha sido eliminado.",
            studyNoticeContent(StudyNotice.Recording(setOf(KeyPermission.NOTIFICATIONS))).text,
        )
    }

    @Test
    fun `hard opt-out stages are ongoing until done`() {
        assertEquals(
            NoticeContent("Eliminando tus datos…", ongoing = true, openStatus = false),
            studyNoticeContent(StudyNotice.HardOptOut(HardOptOutStage.PENDING)),
        )
        assertEquals(
            NoticeContent(
                "Aún no se pudieron eliminar tus datos. Reintentando automáticamente.",
                ongoing = true,
                openStatus = false,
            ),
            studyNoticeContent(StudyNotice.HardOptOut(HardOptOutStage.FAILED)),
        )
        assertEquals(
            NoticeContent(
                "Tus datos fueron eliminados del servidor. Ya puedes desinstalar la aplicación.",
                ongoing = false,
                openStatus = false,
            ),
            studyNoticeContent(StudyNotice.HardOptOut(HardOptOutStage.DONE)),
        )
    }

    @Test
    fun `the end of collection notice can be dismissed`() {
        assertEquals(
            NoticeContent(
                "El periodo de recolección terminó. Gracias por participar. Ya puedes desinstalar la aplicación.",
                ongoing = false,
                openStatus = false,
            ),
            studyNoticeContent(StudyNotice.EndOfCollection),
        )
    }

    private val announcement =
        "El periodo de recolección acabará el 1 de diciembre de 2026. Luego de esa fecha no podrás " +
            "realizar el retiro total del estudio y la aplicación dejará de recolectar información."

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
}
