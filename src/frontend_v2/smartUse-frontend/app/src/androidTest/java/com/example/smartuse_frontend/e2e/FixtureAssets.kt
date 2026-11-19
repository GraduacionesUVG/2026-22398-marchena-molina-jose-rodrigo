package com.example.smartuse_frontend.e2e

import androidx.test.platform.app.InstrumentationRegistry

/** The golden fixtures (models/fixtures, via src/test/resources) as the test APK's assets. */
object FixtureAssets {
    /** The person the seeds use by default (ProfileTest's). */
    const val PERSON = "u40466b"

    /** The test APK's own assets, not the app's. */
    fun text(person: String, name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open("$person/$name").bufferedReader().use { it.readText() }
}
