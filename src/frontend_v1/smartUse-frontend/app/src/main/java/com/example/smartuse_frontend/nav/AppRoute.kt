package com.example.smartuse_frontend.nav

/** The app's top-level screen, chosen in MainActivity. */
enum class AppRoute { LOADING, SETUP, OPT_OUT_DONE, END_OF_STUDY, APP }

/**
 * Terminal states first: the local erase after an opt-out or the end of
 * collection also clears setup-complete (it lives in the session DataStore),
 * and neither may ever fall back into the setup wizard. Opt-out wins.
 */
fun appRoute(setupComplete: Boolean?, hasOptedOut: Boolean?, collectionEnded: Boolean?): AppRoute = when {
    hasOptedOut == null || collectionEnded == null -> AppRoute.LOADING
    hasOptedOut == true -> AppRoute.OPT_OUT_DONE
    collectionEnded == true -> AppRoute.END_OF_STUDY
    setupComplete == null -> AppRoute.LOADING
    setupComplete == false -> AppRoute.SETUP
    else -> AppRoute.APP
}
