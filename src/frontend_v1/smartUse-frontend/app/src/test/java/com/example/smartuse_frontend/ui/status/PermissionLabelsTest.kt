package com.example.smartuse_frontend.ui.status

import com.example.smartuse_frontend.data.KeyPermission
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionLabelsTest {

    @Test
    fun `each permission has a capitalized title`() {
        assertEquals("Acceso al uso", permissionTitle(KeyPermission.USAGE_ACCESS))
        assertEquals("Notificaciones", permissionTitle(KeyPermission.NOTIFICATIONS))
        assertEquals("Ejecución en segundo plano", permissionTitle(KeyPermission.BATTERY_EXEMPTION))
    }

    @Test
    fun `a granted permission reads as granted, a missing one as missing`() {
        assertEquals("Concedido", permissionStateLabel(granted = true))
        assertEquals("Falta", permissionStateLabel(granted = false))
    }

    @Test
    fun `one lost permission is singular`() {
        assertEquals(
            "El permiso de acceso al uso ha sido eliminado.",
            lapseSummary(setOf(KeyPermission.USAGE_ACCESS)),
        )
    }

    @Test
    fun `two lost permissions are joined with y`() {
        assertEquals(
            "Los permisos de acceso al uso y notificaciones han sido eliminados.",
            lapseSummary(setOf(KeyPermission.USAGE_ACCESS, KeyPermission.NOTIFICATIONS)),
        )
    }

    @Test
    fun `three lost permissions use commas and a final y`() {
        assertEquals(
            "Los permisos de acceso al uso, notificaciones y ejecución en segundo plano han sido eliminados.",
            lapseSummary(
                setOf(KeyPermission.USAGE_ACCESS, KeyPermission.NOTIFICATIONS, KeyPermission.BATTERY_EXEMPTION),
            ),
        )
    }

    @Test
    fun `nothing lost gives an empty summary`() {
        assertEquals("", lapseSummary(emptySet()))
    }
}
