package com.example.smartuse_frontend.e2e

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

/** The app's study notification, as the participant sees it: the end notice (3002) if posted, else the recording one (3001). */
object StudyNotification {
    private const val RECORDING_ID = 3001
    private const val END_ID = 3002

    private fun active(context: Context) =
        context.getSystemService(NotificationManager::class.java).activeNotifications

    fun text(context: Context): String? =
        (active(context).firstOrNull { it.id == END_ID } ?: active(context).firstOrNull { it.id == RECORDING_ID })
            ?.notification?.extras
            ?.let { it.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: it.getCharSequence(Notification.EXTRA_TEXT) }
            ?.toString()

    /** The recording notification is the study service's foreground notification. */
    fun isForegroundService(context: Context): Boolean =
        active(context).any { it.id == RECORDING_ID && it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }

    fun awaitText(context: Context, expected: String, timeoutMs: Long = 15_000): String =
        await("the study notification to read \"$expected\" (now: \"${text(context)}\")", timeoutMs) {
            text(context)?.takeIf { it == expected }
        }
}
