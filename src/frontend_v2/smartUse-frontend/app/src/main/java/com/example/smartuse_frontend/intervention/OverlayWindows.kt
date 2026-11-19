package com.example.smartuse_frontend.intervention

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import java.time.Duration

private const val TAG = "OverlayWindows"
private const val TICK_MS = 1_000L

/**
 * The intervention on screen: two overlay windows, plain Views.
 *
 * - Tint: full screen, gray, window alpha [OVERLAY_TINT_ALPHA], not touchable, so
 *   every touch reaches the app underneath.
 * - Panel: transparent strip at the top, its text drawn straight over the tint:
 *   the title with the close X (a white X in a black circle) at its right, the
 *   countdown, and the footer for the cue; the only part that takes touches.
 *
 * Two independent ways out: the close button, and an auto-dismiss posted for
 * [duration] that does not depend on the visible countdown. Main thread only.
 */
class OverlayWindows(
    context: Context,
    private val duration: Duration = OVERLAY_DURATION,
) : OverlayPresenter {

    private val context = ContextThemeWrapper(context.applicationContext, android.R.style.Theme_DeviceDefault_Light)
    private val windowManager = this.context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var tint: View? = null
    private var panel: View? = null
    private var countdown: TextView? = null
    private var closeButton: View? = null
    private var onGone: (() -> Unit)? = null
    private var secondsLeft = 0L

    private val autoDismiss = Runnable { dismiss() }
    private val tick = object : Runnable {
        override fun run() {
            secondsLeft -= 1
            if (secondsLeft <= 0) return
            countdown?.text = countdownText(secondsLeft)
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun show(cue: OverlayCue, onGone: () -> Unit): Boolean {
        if (tint != null || panel != null) return false
        return try {
            val tintView = buildTint()
            tint = tintView
            windowManager.addView(tintView, tintParams())
            val panelView = buildPanel(cue)
            panel = panelView
            windowManager.addView(panelView, panelParams())
            this.onGone = onGone
            handler.postDelayed(autoDismiss, duration.toMillis())
            secondsLeft = duration.seconds
            handler.postDelayed(tick, TICK_MS)
            true
        } catch (e: Exception) {
            // No permission, bad token, OEM refusal: never leave half an overlay.
            Log.w(TAG, "could not show the overlay", e)
            removeWindows()
            false
        }
    }

    override fun dismiss() {
        handler.removeCallbacks(autoDismiss)
        handler.removeCallbacks(tick)
        val wasUp = tint != null || panel != null
        removeWindows()
        val callback = onGone
        onGone = null
        if (wasUp) callback?.invoke()
    }

    /** Taps the close button, as the participant would. */
    @VisibleForTesting
    fun clickClose() {
        closeButton?.performClick()
    }

    // Each window on its own: a failure removing one never leaves the other.
    private fun removeWindows() {
        panel?.let { runCatching { windowManager.removeViewImmediate(it) } }
        tint?.let { runCatching { windowManager.removeViewImmediate(it) } }
        panel = null
        tint = null
        countdown = null
        closeButton = null
    }

    private fun buildTint(): View = View(context).apply { setBackgroundColor(Color.rgb(128, 128, 128)) }

    private fun buildPanel(cue: OverlayCue): View {
        val pad = dp(16)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(
            text(OVERLAY_TITLE, sizeSp = 18f, bold = true),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        closeButton = CloseX(context).apply {
            contentDescription = OVERLAY_CLOSE
            setOnClickListener { dismiss() }
        }.also { header.addView(it, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) }) }
        layout.addView(header)
        countdown = text(countdownText(duration.seconds), sizeSp = 15f).also(layout::addView)
        layout.addView(text(footerText(cue), sizeSp = 15f))
        return layout
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = sizeSp
        // White with a dark shadow: no panel behind it, so it must read over any app under the tint.
        setTextColor(Color.WHITE)
        setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun tintParams() = WindowManager.LayoutParams(
        MATCH_PARENT,
        MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        alpha = OVERLAY_TINT_ALPHA
        title = OVERLAY_TINT_TITLE
    }

    private fun panelParams() = WindowManager.LayoutParams(
        MATCH_PARENT,
        WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Outside its own bounds, touches go to the windows behind it.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP
        title = OVERLAY_PANEL_TITLE
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

/** The close button: a white X in a black circle, the whole 48 dp square tappable. */
private class CloseX(context: Context) : View(context) {
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f * resources.displayMetrics.density
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f * 0.75f
        canvas.drawCircle(cx, cy, r, circle)
        val arm = r * 0.45f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, cross)
        canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, cross)
    }
}
