package com.example.smartuse_frontend.ui.setup

/** The screen the setup wizard is currently showing. */
sealed interface SetupStep {
    object Terms : SetupStep
    object ProfileForm : SetupStep
    object UsageAccess : SetupStep

    /** POST_NOTIFICATIONS — lets the foreground service's persistent notification show. */
    object NotificationAccess : SetupStep

    /** Battery-optimization exemption — keeps the OS from throttling the background jobs. */
    object BatteryOptimization : SetupStep

    /** Display over other apps — lets the study show its intervention overlay. */
    object OverlayAccess : SetupStep

    /** Manufacturer autostart / background settings; only on brands that need it, never verifiable. */
    object Autostart : SetupStep

    /** Optional consent to read the 3 days before signup; only when the phone has usage in them. */
    object EarlierUsage : SetupStep
    object Registering : SetupStep
    object GeneratingKey : SetupStep

    /** Capture + profile freeze from the accepted earlier history, before the dashboard opens. */
    object BuildingProfile : SetupStep
    object Done : SetupStep


    /** POST /users failed. [message] is researcher-facing plain text. */
    data class RegisterError(val message: String) : SetupStep

    /** Keystore key generation failed — shown as an opaque code (RE1040). */
    object KeystoreError : SetupStep
}

/** Where a wizard step sits in the numbered sequence, for the "Paso X de Y" line. */
data class SetupProgress(val current: Int, val total: Int)

// The steps the participant works through, in order. The progress line counts
// exactly these; add a step here and every step's "de Y" follows. Autostart is
// appended only on the brands that get it (AutostartSettings); EarlierUsage
// only while it is on screen (whether it shows is known only on reaching it).
private val NUMBERED_STEPS: List<SetupStep> = listOf(
    SetupStep.Terms,
    SetupStep.ProfileForm,
    SetupStep.UsageAccess,
    SetupStep.NotificationAccess,
    SetupStep.BatteryOptimization,
    SetupStep.OverlayAccess,
)

/** Null for the automatic (registering, key generation) and terminal (done, error) screens. */
fun SetupStep.progress(needsAutostartStep: Boolean = false): SetupProgress? {
    val base = if (needsAutostartStep) NUMBERED_STEPS + SetupStep.Autostart else NUMBERED_STEPS
    val steps = if (this == SetupStep.EarlierUsage) base + SetupStep.EarlierUsage else base
    val index = steps.indexOf(this)
    return if (index < 0) null else SetupProgress(current = index + 1, total = steps.size)
}
