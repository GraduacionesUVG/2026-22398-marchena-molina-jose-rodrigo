package com.example.smartuse_frontend.intervention

import com.example.smartuse_frontend.service.ScreenEvent

/**
 * The intervention trigger. A decision point that chose
 * a pattern shows the overlay now, during use, or not at all: nothing is held
 * (the next session's decision points predict again). Only the footer
 * differs between F, S and both ([OverlayCue]). Main thread only. Keeps no record of interventions: how and
 * when the overlay closed is never known here; [onIntervention] only tells the
 * caller whether it was shown, so the execution rules count shown ones only.
 * Accepted only between [onServiceStarted] and [onServiceStopped]: no overlay
 * after the ending, nor without a SCREEN_OFF listener.
 *
 * [onPreview] is the participant's test from Status: the same overlay, its own
 * footer, outside the execution rules (it never reaches them, so it never
 * counts). A preview never covers an intervention; an intervention replaces a
 * preview.
 */
class InterventionCoordinator(
    private val isInUse: () -> Boolean,
    private val canDrawOverlays: () -> Boolean,
    private val presenter: OverlayPresenter,
    private val logger: (String) -> Unit,
    private val checkMainThread: () -> Unit = {},
) {
    private var serviceAlive = false
    private var studyEnded = false

    var isShowing: Boolean = false
        private set

    /** The overlay up is a preview, which an intervention may replace. */
    private var previewUp = false

    /** Shows the overlay with [cue]'s footer; true only if it is now up. */
    fun onIntervention(cue: OverlayCue): Boolean {
        checkMainThread()
        val reason = when {
            !serviceAlive -> "service not running"
            studyEnded -> "study over"
            isShowing && !previewUp -> "overlay already up"
            !isInUse() -> "phone not in use"
            !canDrawOverlays() -> "no overlay permission"
            else -> null
        }
        if (reason != null) {
            logger("intervention ${cue.name}: $reason, not shown")
            return false
        }
        if (previewUp) {
            logger("intervention ${cue.name}: replaces the preview")
            presenter.dismiss()
        }
        if (show(cue)) {
            logger("intervention ${cue.name}: overlay shown")
            return true
        }
        logger("intervention ${cue.name}: overlay could not be shown")
        return false
    }

    /**
     * Shows the overlay as a test, with the preview footer; true only if it is now up.
     * The participant just tapped a button, so it is in use; without the service
     * running, the auto-dismiss still closes it.
     */
    fun onPreview(): Boolean {
        checkMainThread()
        val reason = when {
            studyEnded -> "study over"
            isShowing -> "overlay already up"
            !canDrawOverlays() -> "no overlay permission"
            else -> null
        }
        if (reason != null) {
            logger("preview: $reason, not shown")
            return false
        }
        val shown = show(OverlayCue.PREVIEW)
        previewUp = shown
        logger(if (shown) "preview: overlay shown" else "preview: overlay could not be shown")
        return shown
    }

    private fun show(cue: OverlayCue): Boolean {
        isShowing = true
        val shown = presenter.show(cue, onGone = {
            isShowing = false
            previewUp = false
        })
        if (!shown) isShowing = false
        return shown
    }

    fun onScreenEvent(event: ScreenEvent) {
        checkMainThread()
        // The overlay only exists during use.
        if (event == ScreenEvent.SCREEN_OFF && isShowing) presenter.dismiss()
    }

    /** The service is up: it listens for SCREEN_OFF, so an overlay can be closed again. */
    fun onServiceStarted() {
        checkMainThread()
        serviceAlive = true
    }

    /** The service is going away (ending, opt-out, or the system): nothing may stay behind. */
    fun onServiceStopped() {
        checkMainThread()
        serviceAlive = false
        presenter.dismiss()
    }

    /** The study ended (StudyEnder): no overlay, no new interventions in this process. */
    fun onStudyEnded() {
        checkMainThread()
        studyEnded = true
        presenter.dismiss()
    }
}
