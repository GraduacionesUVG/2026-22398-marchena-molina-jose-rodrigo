package com.example.smartuse_frontend.nav

import com.example.smartuse_frontend.study.EndingState

/** The app's top-level screen, chosen in MainActivity. */
enum class AppRoute { LOADING, SETUP, APP, END_FORMS, ENDED }

/** The ending first: an ended install never falls back into setup or the dashboard. */
fun appRoute(setupComplete: Boolean?, endingState: EndingState?): AppRoute = when (endingState) {
    null -> AppRoute.LOADING
    EndingState.ENDED -> AppRoute.END_FORMS
    EndingState.FORMS_DONE, EndingState.SENT -> AppRoute.ENDED
    EndingState.LIVE -> when (setupComplete) {
        null -> AppRoute.LOADING
        false -> AppRoute.SETUP
        true -> AppRoute.APP
    }
}