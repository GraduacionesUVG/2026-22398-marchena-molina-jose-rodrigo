package com.example.smartuse_frontend.intervention

/** Puts the intervention on screen. Main thread only. */
interface OverlayPresenter {
    /**
     * Shows the overlay, its footer for [cue]. False if it could not be shown; then nothing is left on
     * screen and [onGone] is never called. Otherwise [onGone] runs exactly once
     * when it goes away, however that happens (it never says how).
     */
    fun show(cue: OverlayCue, onGone: () -> Unit): Boolean

    /** Removes the overlay if it is up; safe to call any time, any number of times. */
    fun dismiss()
}
