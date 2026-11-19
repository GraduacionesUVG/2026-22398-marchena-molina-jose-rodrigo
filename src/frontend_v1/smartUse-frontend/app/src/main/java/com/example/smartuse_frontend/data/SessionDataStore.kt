package com.example.smartuse_frontend.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/** Single Preferences DataStore holding the registered session and the setup-complete flag. */
val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

object SessionKeys {
    val USER_ID = stringPreferencesKey("user_id")
    val AUTH_TOKEN_CIPHERTEXT = stringPreferencesKey("auth_token")       // base64
    val AUTH_TOKEN_IV = stringPreferencesKey("auth_token_iv")            // base64
    val EXPERIMENT_GROUP = stringPreferencesKey("experiment_group")
    val SIGNUP_TS = stringPreferencesKey("signup_ts")                    // ISO-8601
    val PROFILE_AGE = stringPreferencesKey("profile_age")
    val PROFILE_GENDER = stringPreferencesKey("profile_gender")
    val PROFILE_MAJOR = stringPreferencesKey("profile_major")
    val PROFILE_SAS_SV = stringPreferencesKey("profile_sas_sv")
    val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
}
