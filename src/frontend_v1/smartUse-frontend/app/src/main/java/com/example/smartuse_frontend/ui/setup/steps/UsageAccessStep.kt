package com.example.smartuse_frontend.ui.setup.steps

import android.content.Intent
import android.provider.Settings
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
fun UsageAccessStep(
    isGranted: Boolean,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Permiso de acceso al uso")
        Text(
            "La aplicación necesita el permiso \"Acceso a datos de uso\" para registrar el " +
                "tiempo de uso del dispositivo durante el estudio.",
        )
        OutlinedButton(
            onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Abrir ajustes del sistema") }
        Button(onClick = onContinue, enabled = isGranted, modifier = Modifier.fillMaxWidth()) {
            Text(if (isGranted) "Continuar" else "Esperando el permiso…")
        }
    }
}
