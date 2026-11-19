package com.example.smartuse_frontend.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.smartuse_frontend.data.KeyPermission
import com.example.smartuse_frontend.intervention.InterventionRuntime
import com.example.smartuse_frontend.ui.common.AppTopBar
import com.example.smartuse_frontend.ui.common.InfoCard
import com.example.smartuse_frontend.ui.common.PageIntro
import com.example.smartuse_frontend.ui.common.StatusPill
import com.example.smartuse_frontend.ui.common.StatusTone
import com.example.smartuse_frontend.ui.common.openPermissionFix

@Composable
fun StatusScreen(viewModel: StatusViewModel, onBack: () -> Unit, onNavigateToOptOut: () -> Unit) {
    // Coming back from a system settings screen must refresh the permission pills.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.load()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { AppTopBar(title = "Estado", onBack = onBack) },
    ) { innerPadding ->
        when (val state = viewModel.uiState) {
            is StatusUiState.Loading -> Text("Cargando…", modifier = Modifier.padding(innerPadding))
            is StatusUiState.Error -> Text(state.message, modifier = Modifier.padding(innerPadding))
            is StatusUiState.Success -> StatusContent(state, onNavigateToOptOut, Modifier.padding(innerPadding))
        }
    }
}

@Composable
private fun StatusContent(
    state: StatusUiState.Success,
    onNavigateToOptOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageIntro(
            "Revisa si SmartUse tiene los permisos que necesita y cuándo se registraron " +
                "tus datos por última vez.",
        )

        if (state.showLapseCard) {
            InfoCard(title = "Permisos necesarios") {
                Text(lapseSummary(state.permissions.missing), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Activa los permisos para continuar la captura de datos de SmartUse, o sigue los " +
                        "flujos de retiro para eliminar correctamente la aplicación.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = onNavigateToOptOut) { Text("Ver opciones de retiro") }
            }
        }

        InfoCard(title = "Permisos") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KeyPermission.entries.forEach { permission ->
                    val granted = state.permissions.isGranted(permission)
                    StatusRow(
                        title = permissionTitle(permission),
                        pillLabel = permissionStateLabel(granted),
                        tone = if (granted) StatusTone.SUCCESS else StatusTone.ERROR,
                        action = if (granted) null else ({ context.openPermissionFix(permission) }),
                    )
                }
            }
        }

        InfoCard(title = "Registro de datos") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val captureTone = captureTone(state.capture)
                StatusRow(
                    title = "Captura de datos",
                    pillLabel = jobStateLabel(captureTone),
                    tone = captureTone,
                    detail = captureDetail(state.capture),
                )
            }
        }

        // A test of the overlay: outside the execution rules, so it never counts (InterventionCoordinator.onPreview).
        InfoCard(title = "Intervención visual") {
            val canShow = state.permissions.isGranted(KeyPermission.OVERLAY)
            Text(
                "Mira cómo se ve una intervención visual. Es solo una prueba: no cuenta para el estudio.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!canShow) {
                Text(
                    "Activa el permiso de mostrar sobre otras apps para verla.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            OutlinedButton(
                onClick = { InterventionRuntime.coordinator(context).onPreview() },
                enabled = canShow,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Ver intervención de prueba") }
        }
    }
}

/** One line of a Status card: title (and detail) on the left, a short state pill on the right. */
@Composable
private fun StatusRow(
    title: String,
    pillLabel: String,
    tone: StatusTone,
    detail: String? = null,
    action: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (action != null) {
                TextButton(onClick = action, contentPadding = PaddingValues(0.dp)) { Text("Corregir") }
            }
        }
        StatusPill(label = pillLabel, tone = tone)
    }
}
