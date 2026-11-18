package com.example.smartuse_frontend.nav

/**
 * Which screen an external launch (a notification tap) asks the app to open. An
 * intent extra rather than a URI scheme: no new intent filter, nothing new
 * exposed to other apps.
 */
object LaunchDestination {
    const val EXTRA = "destination"
    const val STATUS = "status"

    fun route(destination: String?): String? = when (destination) {
        STATUS -> Routes.STATUS
        else -> null
    }
}
