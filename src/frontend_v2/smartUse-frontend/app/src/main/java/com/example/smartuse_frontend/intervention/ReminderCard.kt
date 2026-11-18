package com.example.smartuse_frontend.intervention

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.nav.LaunchDestination

private const val TAG = "ReminderCard"
private const val REMINDER_CARD_DURATION_MS = 30_000L

const val REMINDER_CARD_TEXT = "Estudio SmartUse: faltan los formularios finales."
const val REMINDER_CARD_NOW = "Completar ahora"
const val REMINDER_CARD_LATER = "Más tarde"
internal const val REMINDER_CARD_TITLE = "SmartUse reminder card"

/**
 * The end-forms reminder over the screen at each unlock while the forms are
 * pending: one small touchable panel at the top, no tint, no
 * countdown, so it never looks like an intervention. Gone with either button,
 * after 30 s and at screen-off. Records nothing. Main thread only.
 */
class ReminderCard(
    context: Context,
    private val openForms: () -> Unit = { openEndForms(context.applicationContext) },
    private val durationMs: Long = REMINDER_CARD_DURATION_MS,
) {
    private val context = ContextThemeWrapper(context.applicationContext, android.R.style.Theme_DeviceDefault_Light)
    private val windowManager = this.context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var card: View? = null
    private var nowButton: Button? = null
    private var laterButton: Button? = null
    private val autoRemove = Runnable { dismiss() }

    val isShowing: Boolean get() = card != null

    fun show(): Boolean {
        if (card != null) return true
        return try {
            val view = build()
            card = view
            windowManager.addView(view, params())
            handler.postDelayed(autoRemove, durationMs)
            true
        } catch (e: Exception) {
            // No permission, bad token, OEM refusal: never leave a half-added window.
            Log.w(TAG, "could not show the reminder card", e)
            removeWindow()
            false
        }
    }

    fun dismiss() {
        handler.removeCallbacks(autoRemove)
        removeWindow()
    }

    /** Taps "Completar ahora", as the participant would. */
    @VisibleForTesting
    fun clickNow() {
        nowButton?.performClick()
    }

    /** Taps "Más tarde", as the participant would. */
    @VisibleForTesting
    fun clickLater() {
        laterButton?.performClick()
    }

    private fun removeWindow() {
        card?.let { runCatching { windowManager.removeViewImmediate(it) } }
        card = null
        nowButton = null
        laterButton = null
    }

    private fun build(): View {
        val pad = dp(16)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(
            TextView(context).apply {
                text = REMINDER_CARD_TEXT
                textSize = 16f
                setTextColor(Color.BLACK)
                setPadding(0, 0, 0, dp(8))
            },
        )
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        nowButton = button(REMINDER_CARD_NOW) {
            dismiss()
            openForms()
        }.also(buttons::addView)
        laterButton = button(REMINDER_CARD_LATER) { dismiss() }.also(buttons::addView)
        layout.addView(buttons)
        return layout
    }

    private fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        minHeight = dp(48)
        setOnClickListener { onClick() }
    }

    private fun params() = WindowManager.LayoutParams(
        MATCH_PARENT,
        WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Outside its own bounds, touches go to the windows behind it.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP
        title = REMINDER_CARD_TITLE
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

/** Opens the app; while the forms are pending its root is the end forms. */
fun openEndForms(context: Context) {
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(LaunchDestination.EXTRA, LaunchDestination.END_FORMS),
    )
}