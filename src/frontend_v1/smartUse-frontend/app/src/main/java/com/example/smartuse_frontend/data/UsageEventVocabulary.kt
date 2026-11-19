package com.example.smartuse_frontend.data

import android.app.usage.UsageEvents

// The narrowed UsageEvents.Event vocabulary: screen on, screen off, and the
// "user interaction" signal (an app coming to the foreground) — everything
// else in the raw event stream is discarded at capture time, including
// KEYGUARD_SHOWN/HIDDEN and ACTIVITY_PAUSED (redundant: an app's usage
// interval end is just whichever event comes next). SCREEN_INTERACTIVE/
// NON_INTERACTIVE require API 28+, fine here since the app's minSdk is 33. MOVE_TO_FOREGROUND is the
// API 21+ constant backing what API 29+ renamed to ACTIVITY_RESUMED.
val CAPTURED_EVENT_TYPES: Set<Int> = setOf(
    UsageEvents.Event.SCREEN_INTERACTIVE,
    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
    UsageEvents.Event.MOVE_TO_FOREGROUND,
)

// Events that carry an app_hash (they report a foreground app); screen
// events are boundary-only and always have a null app_hash.
val APP_ATTRIBUTED_EVENT_TYPES: Set<Int> = setOf(
    UsageEvents.Event.MOVE_TO_FOREGROUND,
)

fun usageEventTypeName(type: Int): String = when (type) {
    UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
    UsageEvents.Event.MOVE_TO_FOREGROUND -> "ACTIVITY_RESUMED"
    else -> "OTHER($type)"
}
