package com.example.smartuse_frontend.ui.setup.steps

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
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

// BatteryLife flags this intent for Play Store policy; the APK is distributed
// manually (see the manifest note), so the policy does not apply.
@SuppressLint("BatteryLife")
@Composable
fun BatteryOptimizationStep(
    isGranted: Boolean,
    onPermissionResult: () -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val requestExemption = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { onPermissionResult() }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ejecución en segundo plano")
        Text(
            "Para que la captura de datos no se interrumpa, Android debe permitir que la " +
                "aplicación funcione en segundo plano. Sin este permiso, el sistema puede " +
                "pausarla cuando no la usas. La aplicación no consume una cantidad " +
                "significativa de batería.",
        )
        Button(
            onClick = {
                requestExemption.launch(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
            enabled = !isGranted,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Permitir ejecución en segundo plano") }
        // Fallback: some manufacturers ignore the direct dialog, so the participant
        // can grant it from the system's battery-optimization list instead.
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Abrir ajustes de batería") }
        Button(onClick = onContinue, enabled = isGranted, modifier = Modifier.fillMaxWidth()) {
            Text(if (isGranted) "Continuar" else "Esperando el permiso…")
        }
    }
}
