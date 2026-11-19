package com.example.smartuse_frontend.ui.setup.steps

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
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
import androidx.core.net.toUri
import com.example.smartuse_frontend.ui.setup.AutostartSettings
import com.example.smartuse_frontend.ui.setup.AutostartTarget

@Composable
fun AutostartStep(onContinue: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Inicio automático")
        Text(
            "Algunos fabricantes detienen las aplicaciones en segundo plano aunque tengan permiso. " +
                "Abre los ajustes y permite que SmartUse se inicie automáticamente o funcione sin " +
                "restricciones de batería. Si no encuentras la opción, puedes continuar.",
        )
        OutlinedButton(onClick = { context.openAutostartSettings() }, modifier = Modifier.fillMaxWidth()) {
            Text("Abrir ajustes")
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continuar") }
    }
}

/**
 * Tries the brand's known screens in order, then the app's own settings page.
 * A missing or non-exported vendor screen throws; that just moves on to the next.
 */
private fun Context.openAutostartSettings() {
    for (target in AutostartSettings.targets(Build.MANUFACTURER)) {
        val intent = when (target) {
            is AutostartTarget.Screen -> Intent().setComponent(ComponentName(target.packageName, target.className))
            AutostartTarget.AppDetails ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
        }
        try {
            startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            continue
        } catch (e: SecurityException) {
            continue
        }
    }
}
