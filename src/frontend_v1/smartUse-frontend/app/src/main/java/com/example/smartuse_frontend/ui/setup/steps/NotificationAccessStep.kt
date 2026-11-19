package com.example.smartuse_frontend.ui.setup.steps

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun NotificationAccessStep(
    isGranted: Boolean,
    onPermissionResult: () -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onPermissionResult() }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Permiso de notificaciones")
        Text(
            "Mientras el estudio esté activo, la aplicación mantiene una notificación " +
                "permanente y discreta. Necesita el permiso de notificaciones para mostrarla.",
        )
        Button(
            onClick = { requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
            enabled = !isGranted,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Permitir notificaciones") }
        // Fallback: after the system dialog has been declined it no longer
        // appears, so the participant has to grant it from settings.
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Abrir ajustes de notificaciones") }
        Button(onClick = onContinue, enabled = isGranted, modifier = Modifier.fillMaxWidth()) {
            Text(if (isGranted) "Continuar" else "Esperando el permiso…")
        }
    }
}
