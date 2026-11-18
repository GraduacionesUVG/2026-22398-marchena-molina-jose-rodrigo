package com.example.smartuse_frontend.network

import com.example.smartuse_frontend.BuildConfig

/**
 * The backend base URL every API client uses. Instrumented tests point it at an
 * in-process fake backend (they share the app's process); nothing in the app
 * ever sets it, so release always uses the URL configured at build time.
 */
object ApiEndpoint {
    @Volatile private var override: String? = null

    val baseUrl: String get() = override ?: BuildConfig.SMARTUSE_BASE_URL

    fun overrideForTests(url: String?) {
        override = url
    }
}
