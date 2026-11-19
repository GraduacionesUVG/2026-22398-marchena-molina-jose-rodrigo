package com.example.smartuse_frontend.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.nav.LaunchDestination

private const val TAG = "StudyNotifier"

/**
 * The app's only notification: silent (IMPORTANCE_LOW, setSilent), one fixed id
 * so every post replaces the last.
 */
class RealStudyNotifier(private val context: Context) : StudyNotifier {

    private val manager = NotificationManagerCompat.from(context)

    // Checked just above; a revoked permission (SecurityException) or any other
    // platform error is logged, never thrown.
    @SuppressLint("MissingPermission")
    override fun show(notice: StudyNotice) {
        if (!manager.areNotificationsEnabled()) return
        try {
            ensureChannel()
            val content = studyNoticeContent(notice)
            val intent = Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            if (content.openStatus) intent.putExtra(LaunchDestination.EXTRA, LaunchDestination.STATUS)
            val tap = PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(
                    if (content.openStatus) android.R.drawable.stat_sys_warning
                    else android.R.drawable.stat_notify_sync_noanim,
                )
                .setContentTitle("SmartUse")
                .setContentText(content.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
                .setOngoing(content.ongoing)
                .setAutoCancel(!content.ongoing)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(tap)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "could not post the study notification", e)
        }
    }

    override fun clear() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        val system = context.getSystemService(NotificationManager::class.java)
        // Idempotent. Importance can't be raised by the app once created; the
        // participant can change it, which is their call.
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        // The old permission-watcher channel (alerting): gone, so the app shows one category only.
        system.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }

    companion object {
        const val CHANNEL_ID = "study_status"
        const val CHANNEL_NAME = "Estado del estudio"
        private const val LEGACY_CHANNEL_ID = "permission_watch"
        private const val NOTIFICATION_ID = 3001
        private const val REQUEST_CODE = 3001
    }
}
