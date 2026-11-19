package com.example.smartuse_frontend.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AppColorScheme = lightColorScheme(
    primary = ClinicalPrimary,
    onPrimary = ClinicalOnPrimary,
    primaryContainer = ClinicalPrimaryContainer,
    onPrimaryContainer = ClinicalOnPrimaryContainer,
    inversePrimary = ClinicalInversePrimary,
    secondary = ClinicalSecondary,
    onSecondary = ClinicalOnSecondary,
    secondaryContainer = ClinicalSecondaryContainer,
    onSecondaryContainer = ClinicalOnSecondaryContainer,
    tertiary = ClinicalTertiary,
    onTertiary = ClinicalOnTertiary,
    tertiaryContainer = ClinicalTertiaryContainer,
    onTertiaryContainer = ClinicalOnTertiaryContainer,
    error = ClinicalError,
    onError = ClinicalOnError,
    errorContainer = ClinicalErrorContainer,
    onErrorContainer = ClinicalOnErrorContainer,
    background = ClinicalBackground,
    onBackground = ClinicalOnBackground,
    surface = ClinicalSurface,
    onSurface = ClinicalOnSurface,
    surfaceVariant = ClinicalSurfaceVariant,
    onSurfaceVariant = ClinicalOnSurfaceVariant,
    outline = ClinicalOutline,
    outlineVariant = ClinicalOutlineVariant,
    inverseSurface = ClinicalInverseSurface,
    inverseOnSurface = ClinicalInverseOnSurface,
    surfaceTint = ClinicalSurfaceTint,
    surfaceDim = ClinicalSurfaceDim,
    surfaceBright = ClinicalSurfaceBright,
    surfaceContainerLowest = ClinicalSurfaceContainerLowest,
    surfaceContainerLow = ClinicalSurfaceContainerLow,
    surfaceContainer = ClinicalSurfaceContainer,
    surfaceContainerHigh = ClinicalSurfaceContainerHigh,
    surfaceContainerHighest = ClinicalSurfaceContainerHighest,
    primaryFixed = ClinicalPrimaryFixed,
    primaryFixedDim = ClinicalPrimaryFixedDim,
    onPrimaryFixed = ClinicalOnPrimaryFixed,
    onPrimaryFixedVariant = ClinicalOnPrimaryFixedVariant,
    secondaryFixed = ClinicalSecondaryFixed,
    secondaryFixedDim = ClinicalSecondaryFixedDim,
    onSecondaryFixed = ClinicalOnSecondaryFixed,
    onSecondaryFixedVariant = ClinicalOnSecondaryFixedVariant,
    tertiaryFixed = ClinicalTertiaryFixed,
    tertiaryFixedDim = ClinicalTertiaryFixedDim,
    onTertiaryFixed = ClinicalOnTertiaryFixed,
    onTertiaryFixedVariant = ClinicalOnTertiaryFixedVariant,
)

// Single light theme — DESIGN.md defines no dark palette.
@Composable
fun SmartUsefrontendTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}
