package com.example.smartuse_frontend.ui.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.smartuse_frontend.data.PermissionChecker
import com.example.smartuse_frontend.ui.setup.steps.AutostartStep
import com.example.smartuse_frontend.ui.setup.steps.BatteryOptimizationStep
import com.example.smartuse_frontend.ui.setup.steps.EarlierUsageStep
import com.example.smartuse_frontend.ui.setup.steps.NotificationAccessStep
import com.example.smartuse_frontend.ui.setup.steps.OverlayAccessStep
import com.example.smartuse_frontend.ui.setup.steps.ProfileFormStep
import com.example.smartuse_frontend.ui.setup.steps.ProgressStep
import com.example.smartuse_frontend.ui.setup.steps.SetupDoneStep
import com.example.smartuse_frontend.ui.setup.steps.SetupErrorStep
import com.example.smartuse_frontend.ui.setup.steps.TermsStep
import com.example.smartuse_frontend.ui.setup.steps.UsageAccessStep

@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    permissionChecker: PermissionChecker,
) {
    // Re-check every grant each time the user returns from settings / a system dialog.
    val lifecycleOwner = LocalLifecycleOwner.current
    var usageGranted by remember { mutableStateOf(permissionChecker.hasUsageAccess()) }
    var notificationsGranted by remember { mutableStateOf(permissionChecker.hasNotificationPermission()) }
    var batteryExempt by remember { mutableStateOf(permissionChecker.hasBatteryOptimizationExemption()) }
    var overlayGranted by remember { mutableStateOf(permissionChecker.canDrawOverlays()) }
    val refreshPermissions = {
        usageGranted = permissionChecker.hasUsageAccess()
        notificationsGranted = permissionChecker.hasNotificationPermission()
        batteryExempt = permissionChecker.hasBatteryOptimizationExemption()
        overlayGranted = permissionChecker.canDrawOverlays()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            viewModel.step.progress(viewModel.needsAutostartStep)?.let { SetupProgressHeader(it) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val step = viewModel.step) {
                    SetupStep.Terms -> TermsStep(onAccept = viewModel::acceptTerms)
                    SetupStep.ProfileForm -> ProfileFormStep(onSubmit = viewModel::submitProfile)
                    SetupStep.UsageAccess -> UsageAccessStep(
                        isGranted = usageGranted,
                        onContinue = viewModel::onUsageAccessContinue,
                    )
                    SetupStep.NotificationAccess -> NotificationAccessStep(
                        isGranted = notificationsGranted,
                        onPermissionResult = refreshPermissions,
                        onContinue = viewModel::onNotificationAccessContinue,
                    )
                    SetupStep.BatteryOptimization -> BatteryOptimizationStep(
                        isGranted = batteryExempt,
                        onPermissionResult = refreshPermissions,
                        onContinue = viewModel::onBatteryOptimizationContinue,
                    )
                    SetupStep.OverlayAccess -> OverlayAccessStep(
                        isGranted = overlayGranted,
                        onContinue = viewModel::onOverlayAccessContinue,
                    )
                    SetupStep.Autostart -> AutostartStep(onContinue = viewModel::onAutostartContinue)
                    SetupStep.EarlierUsage -> EarlierUsageStep(
                        enabled = !viewModel.inFlight,
                        onAccept = viewModel::acceptEarlierUsage,
                        onReject = viewModel::rejectEarlierUsage,
                    )
                    SetupStep.Registering -> ProgressStep("Registrando el dispositivo…")
                    SetupStep.GeneratingKey -> ProgressStep("Preparando el almacenamiento seguro…")
                    SetupStep.BuildingProfile -> ProgressStep("Calculando tu perfil de uso…")
                    SetupStep.Done -> SetupDoneStep()
                    is SetupStep.RegisterError -> SetupErrorStep(
                        message = step.message,
                        onRetry = viewModel::retryRegistration,
                        onBackToForm = viewModel::backToForm,
                    )
                    SetupStep.KeystoreError -> SetupErrorStep(
                        message = "No se pudo completar la configuración (RE1040).",
                        onRetry = viewModel::retryKeyGeneration,
                        onBackToForm = null,
                    )
                }
            }
        }
    }
}
