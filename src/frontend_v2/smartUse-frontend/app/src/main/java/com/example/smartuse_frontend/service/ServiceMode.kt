package com.example.smartuse_frontend.service

import com.example.smartuse_frontend.study.EndingState

/** What the study service does (STUDY while live, then the reminder and sending modes of the ending). Every start path asks it. */
enum class ServiceMode {
    /** Not running: before setup, a live study past its window (its ending is about to run), or after the confirmed send. */
    OFF,

    /** The study runs: the study notification, the interventions, the screen observations. */
    STUDY,

    /** Waiting for the end forms: the reminder notification and the unlock card. */
    FORMS_REMINDER,

    /** Forms submitted, the final send pending: the "sending" notification only. */
    SENDING;

    companion object {
        fun of(state: EndingState, isStudyLive: Boolean): ServiceMode = when (state) {
            EndingState.LIVE -> if (isStudyLive) STUDY else OFF
            EndingState.ENDED -> FORMS_REMINDER
            EndingState.FORMS_DONE -> SENDING
            EndingState.SENT -> OFF
        }
    }
}

/** What the reminder card does on a screen event. */
enum class CardAction { SHOW, REMOVE, NONE }

/** At each unlock while the forms are pending, if it may be drawn; gone at screen-off. Nothing is recorded. */
fun reminderCardAction(mode: ServiceMode, event: ScreenEvent, canDrawOverlays: Boolean): CardAction = when {
    event == ScreenEvent.SCREEN_OFF -> CardAction.REMOVE
    event == ScreenEvent.USER_PRESENT && mode == ServiceMode.FORMS_REMINDER && canDrawOverlays -> CardAction.SHOW
    else -> CardAction.NONE
}