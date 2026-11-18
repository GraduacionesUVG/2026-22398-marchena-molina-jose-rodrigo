package com.example.smartuse_frontend.ui.setup.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.data.KeyPermission
import com.example.smartuse_frontend.ui.common.openPermissionFix

/** Display over other apps. Detected on return from Settings (SetupScreen re-checks on resume). */
@Composable
fun OverlayAccessStep(
    isGranted: Boolean,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Mostrar sobre otras apps")
        Text(
            "Durante el estudio, SmartUse puede mostrar un aviso breve sobre la pantalla " +
                "mientras usas el teléfono. Para eso, Android debe permitir que la aplicación " +
                "se muestre sobre otras aplicaciones. Busca SmartUse en la lista y activa el permiso.",
        )
        Button(
            onClick = { context.openPermissionFix(KeyPermission.OVERLAY) },
            enabled = !isGranted,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Abrir ajustes") }
        Button(onClick = onContinue, enabled = isGranted, modifier = Modifier.fillMaxWidth()) {
            Text(if (isGranted) "Continuar" else "Esperando el permiso…")
        }
    }
}
