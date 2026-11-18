package com.example.smartuse_frontend.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.smartuse_frontend.data.RealPermissionChecker
import com.example.smartuse_frontend.inference.DecisionRuntime
import com.example.smartuse_frontend.inference.ScreenObservations
import com.example.smartuse_frontend.intervention.InterventionRuntime
import com.example.smartuse_frontend.intervention.ReminderCard
import com.example.smartuse_frontend.jobs.JobRuntime
import com.example.smartuse_frontend.jobs.RealJobStateStore
import com.example.smartuse_frontend.notification.RealStudyNotifier
import com.example.smartuse_frontend.notification.STUDY_NOTIFICATION_ID
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.buildStudyNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

private const val TAG = "StudyService"

/**
 * Keeps the process alive from setup until the final send is confirmed; its
 * notification is the study notification (same id: RealStudyNotifier updates
 * it in place). What it does follows the ending state (ServiceMode): while the study runs it hands screen events to the
 * decision-point scheduler, the intervention trigger
 * (InterventionRuntime) and the timeline's observations (ScreenObservations);
 * the decision points run in its scope. After the ending it only reminds
 * (notification, unlock card) until the send is confirmed, then stops.
 */
class StudyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Main thread only. OFF until the first read, so no event acts before it.
    private var mode = ServiceMode.OFF
    private val reminderCard by lazy { ReminderCard(this) }
    private val handler = Handler(Looper.getMainLooper())

    // Main thread only. Its timers run only while the screen is on, so the device is awake.
    private val decisions by lazy {
        DecisionScheduler(
            schedule = { atMs, action ->
                val runnable = Runnable(action)
                handler.postAtTime(runnable, SystemClock.uptimeMillis() + (atMs - System.currentTimeMillis()).coerceAtLeast(0))
                Cancellable { handler.removeCallbacks(runnable) }
            },
            onPoint = { kind, t -> scope.launch { DecisionRuntime.runner(this@StudyService).run(kind, Instant.ofEpochMilli(t)) } },
        )
    }

    private val listener = ScreenEventListener { event ->
        Log.d(TAG, "screen event: $event ($mode)")
        if (mode == ServiceMode.STUDY) {
            val now = System.currentTimeMillis()
            screenStateFor(event)?.let { ScreenObservations.log.record(now, it) }
            InterventionRuntime.coordinator(this).onScreenEvent(event)
            when (event) {
                ScreenEvent.SCREEN_ON -> decisions.onScreenOn(now)
                ScreenEvent.USER_PRESENT -> decisions.onUserPresent(now)
                ScreenEvent.SCREEN_OFF -> decisions.onScreenOff(now)
            }
        }
        when (reminderCardAction(mode, event, RealPermissionChecker(this).canDrawOverlays())) {
            CardAction.SHOW -> reminderCard.show()
            CardAction.REMOVE -> reminderCard.dismiss()
            CardAction.NONE -> Unit
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenEventFor(intent.action)?.let(listener::onScreenEvent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The timeline at a decision point needs the screen state USM hasn't published yet.
        ScreenObservations.log.startObserving(System.currentTimeMillis(), getSystemService(PowerManager::class.java).isInteractive)
        InterventionRuntime.coordinator(this).onServiceStarted()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // EXPORTED: USER_PRESENT comes from SystemUI (not the system uid), which a
        // NOT_EXPORTED receiver never gets. All three are protected broadcasts, so
        // only the OS can send them; other apps still can't reach this receiver.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        // The ending moves the running service from mode to mode.
        scope.launch {
            RealJobStateStore(this@StudyService).endMarks.map { it.state }.distinctUntilChanged().collect { applyMode() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always first, within the 5 s Android allows after startForegroundService,
        // even when the mode below stops the service again. The real text
        // replaces this right after.
        val entered = enterForeground(
            startForeground = {
                startForeground(STUDY_NOTIFICATION_ID, buildStudyNotification(this, StudyNotice.Recording(emptySet())))
            },
            stopSelf = ::stopSelf,
            logger = { Log.w(TAG, it) },
        )
        if (!entered) return START_NOT_STICKY
        scope.launch {
            // Covers the sticky restart, which no caller checks.
            applyMode()
            JobRuntime.endingCleanup(this@StudyService).ensure()
        }
        return START_STICKY
    }

    // One at a time, each reading the mode under the lock: a start racing the
    // ending must not apply its stale read (and its notice) after the new mode.
    private suspend fun applyMode() = JobRuntime.noticeLock.withLock { applyModeLocked() }

    private suspend fun applyModeLocked() {
        val next = JobRuntime.serviceMode(this)
        withContext(Dispatchers.Main) {
            mode = next
            if (next != ServiceMode.FORMS_REMINDER) reminderCard.dismiss()
            // No decision points after the study: they restart with the next session if it ever runs again.
            if (next != ServiceMode.STUDY) decisions.stop()
        }
        when (next) {
            ServiceMode.STUDY -> JobRuntime.postRecordingNotice(this)
            ServiceMode.FORMS_REMINDER -> RealStudyNotifier(this).show(StudyNotice.FormsPending)
            ServiceMode.SENDING -> RealStudyNotifier(this).show(StudyNotice.Sending)
            ServiceMode.OFF -> {
                Log.d(TAG, "nothing left to do: stopping")
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        // Ending, the confirmed send or the system: no overlay, card or held prediction outlives the service.
        InterventionRuntime.coordinator(this).onServiceStopped()
        decisions.stop()
        reminderCard.dismiss()
        ScreenObservations.log.stopObserving()
        unregisterReceiver(receiver)
        scope.cancel()
        super.onDestroy()
    }
}