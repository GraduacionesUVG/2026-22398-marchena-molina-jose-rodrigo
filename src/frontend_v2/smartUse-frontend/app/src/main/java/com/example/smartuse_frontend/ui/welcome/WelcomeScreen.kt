package com.example.smartuse_frontend.ui.welcome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.smartuse_frontend.data.KeyPermission
import com.example.smartuse_frontend.ui.common.AppTopBar
import com.example.smartuse_frontend.ui.common.PageIntro
import com.example.smartuse_frontend.ui.common.StatusPill
import com.example.smartuse_frontend.ui.common.StatusTone
import com.example.smartuse_frontend.study.cutoffAnnouncement
import com.example.smartuse_frontend.ui.common.StudyContactInfo
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun WelcomeScreen(
    viewModel: WelcomeViewModel,
    onNavigateToStatus: () -> Unit,
    onNavigateToDataOverview: () -> Unit,
    onNavigateToOptOut: () -> Unit,
    onNavigateToTerms: () -> Unit,
) {
    // Returning from a system settings screen should clear the banner.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.load()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (val state = viewModel.uiState) {
        is WelcomeUiState.Loading -> CircularProgressIndicator()
        is WelcomeUiState.Error -> Text(state.message)
        is WelcomeUiState.Success -> WelcomeContent(
            announcedCutoff = state.announcedCutoff,
            missingPermissions = state.missingPermissions,
            interventionsBox = state.interventionsBox,
            onNavigateToStatus = onNavigateToStatus,
            onNavigateToDataOverview = onNavigateToDataOverview,
            onNavigateToOptOut = onNavigateToOptOut,
            onNavigateToTerms = onNavigateToTerms,
        )
    }
}

@Composable
private fun WelcomeContent(
    announcedCutoff: LocalDate?,
    missingPermissions: Set<KeyPermission>,
    interventionsBox: InterventionsBox?,
    onNavigateToStatus: () -> Unit,
    onNavigateToDataOverview: () -> Unit,
    onNavigateToOptOut: () -> Unit,
    onNavigateToTerms: () -> Unit,
) {
    Scaffold(
        topBar = { AppTopBar(title = "SmartUse") },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (missingPermissions.isNotEmpty()) {
                PermissionBanner(onNavigateToStatus)
            }

            PageIntro(
                "Gracias por participar en el estudio. Desde aquí puedes revisar su estado, tus datos " +
                    "o tu participación.",
            )

            when (interventionsBox) {
                is InterventionsBox.StartsBy -> InfoBanner(interventionsStartLine(interventionsBox.at, ZoneId.systemDefault()))
                InterventionsBox.Active -> StatusPill(INTERVENTIONS_ACTIVE, StatusTone.SUCCESS)
                null -> Unit
            }

            if (announcedCutoff != null) {
                CutoffBanner(announcedCutoff)
            }

            MenuGridRow {
                WelcomeMenuTile("Estado", Icons.Filled.CheckCircle, onNavigateToStatus, Modifier.weight(1f))
                WelcomeMenuTile("Resumen de datos", Icons.Filled.DateRange, onNavigateToDataOverview, Modifier.weight(1f))
            }
            MenuGridRow {
                WelcomeMenuTile(
                    "Retirarse del estudio",
                    Icons.AutoMirrored.Filled.ExitToApp,
                    onNavigateToOptOut,
                    Modifier.weight(1f),
                )
                WelcomeMenuTile("Términos y condiciones", Icons.Filled.Info, onNavigateToTerms, Modifier.weight(1f))
            }

            Spacer(Modifier.weight(1f))

            StudyContactInfo(heading = "Preguntas o solicitudes de eliminación de datos")
        }
    }
}

@Composable
private fun PermissionBanner(onViewStatus: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Faltan permisos necesarios",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onViewStatus) { Text("Ver estado") }
        }
    }
}

@Composable
private fun CutoffBanner(cutoff: LocalDate) = InfoBanner(cutoffAnnouncement(cutoff))

@Composable
private fun InfoBanner(text: String) {
    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** Two tiles side by side, stretched to the taller one so the grid stays even. */
@Composable
private fun MenuGridRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun WelcomeMenuTile(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.fillMaxHeight().heightIn(min = 104.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
