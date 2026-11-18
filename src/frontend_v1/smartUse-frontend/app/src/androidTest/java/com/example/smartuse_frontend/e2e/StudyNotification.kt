package com.example.smartuse_frontend.e2e

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

/** The app's one study notification (RealStudyNotifier, id 3001), as the participant sees it. */
object StudyNotification {
    private const val ID = 3001

    fun text(context: Context): String? =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == ID }?.notification?.extras
            ?.let { it.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: it.getCharSequence(Notification.EXTRA_TEXT) }
            ?.toString()

    fun awaitText(context: Context, expected: String, timeoutMs: Long = 15_000): String =
        await("the study notification to read \"$expected\" (now: \"${text(context)}\")", timeoutMs) {
            text(context)?.takeIf { it == expected }
        }
}
