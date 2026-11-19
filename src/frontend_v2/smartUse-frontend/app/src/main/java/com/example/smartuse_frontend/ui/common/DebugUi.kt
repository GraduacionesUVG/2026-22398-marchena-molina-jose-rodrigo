package com.example.smartuse_frontend.ui.common

/**
 * Debug-build switches for the screenshot tour (androidTest `ScreenshotTour`).
 * A release build ignores both: every use is behind `BuildConfig.DEBUG`.
 */
object DebugUi {
    /** Whether Data shows its developer tools (the `[DEBUG]` card). */
    @Volatile var showTools: Boolean = true

    /**
     * Setup asks the earlier-usage question as if the phone had usage in the
     * 3 days before today (a fresh test device never has any).
     */
    @Volatile var assumeEarlierUsage: Boolean = false
}
