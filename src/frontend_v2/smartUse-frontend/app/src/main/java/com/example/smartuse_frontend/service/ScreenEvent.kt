package com.example.smartuse_frontend.service

import android.content.Intent

/** The screen events the study service listens for. */
enum class ScreenEvent { SCREEN_ON, USER_PRESENT, SCREEN_OFF }

fun screenEventFor(action: String?): ScreenEvent? = when (action) {
    Intent.ACTION_SCREEN_ON -> ScreenEvent.SCREEN_ON
    Intent.ACTION_USER_PRESENT -> ScreenEvent.USER_PRESENT
    Intent.ACTION_SCREEN_OFF -> ScreenEvent.SCREEN_OFF
    else -> null
}

/**
 * The screen state an event sets, for the inference tail: a canonical use
 * epoch starts at screen-on (SCREEN_INTERACTIVE), not at unlock.
 */
fun screenStateFor(event: ScreenEvent): Boolean? = when (event) {
    ScreenEvent.SCREEN_ON -> true
    ScreenEvent.SCREEN_OFF -> false
    ScreenEvent.USER_PRESENT -> null
}

/** Where the service hands screen events: the intervention trigger and the inference tail's observations. */
fun interface ScreenEventListener {
    fun onScreenEvent(event: ScreenEvent)
}
