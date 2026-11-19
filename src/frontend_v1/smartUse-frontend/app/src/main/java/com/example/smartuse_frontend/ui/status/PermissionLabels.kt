package com.example.smartuse_frontend.ui.status

import com.example.smartuse_frontend.data.KeyPermission

/** "Acceso al uso", "Notificaciones", "Ejecución en segundo plano". */
fun permissionTitle(permission: KeyPermission): String = permission.labelEs.replaceFirstChar { it.uppercase() }

fun permissionStateLabel(granted: Boolean): String = if (granted) "Concedido" else "Falta"

/** "El permiso de A ha sido eliminado." / "Los permisos de A, B y C han sido eliminados." */
fun lapseSummary(missing: Set<KeyPermission>): String {
    val names = missing.map { it.labelEs }
    return when (names.size) {
        0 -> ""
        1 -> "El permiso de ${names[0]} ha sido eliminado."
        else -> "Los permisos de ${names.dropLast(1).joinToString(", ")} y ${names.last()} han sido eliminados."
    }
}
