package com.example.smartuse_frontend.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * DataStore-backed session store. `auth_token` is sealed with an AES-256/GCM key
 * held non-exportably in the Android Keystore (alias [AES_ALIAS]); the IV is
 * stored alongside the ciphertext.
 */
class RealCredentialStore(private val context: Context) : CredentialStore {

    override suspend fun saveSession(session: PersistedSession) {
        val (ciphertext, iv) = encrypt(session.authToken)
        context.sessionDataStore.edit { prefs ->
            prefs[SessionKeys.USER_ID] = session.userId
            prefs[SessionKeys.AUTH_TOKEN_CIPHERTEXT] = base64(ciphertext)
            prefs[SessionKeys.AUTH_TOKEN_IV] = base64(iv)
            prefs[SessionKeys.SIGNUP_TS] = session.signupTs.toString()
            prefs[SessionKeys.PROFILE_AGE] = session.profile.age.toString()
            prefs[SessionKeys.PROFILE_GENDER] = session.profile.gender
            prefs[SessionKeys.PROFILE_MAJOR] = session.profile.universityMajor
            prefs[SessionKeys.PROFILE_SAS_SV] = session.profile.sasSvScore.toString()
        }
    }

    override suspend fun userId(): String? = read(SessionKeys.USER_ID)

    override suspend fun authToken(): String? {
        val prefs = context.sessionDataStore.data.first()
        val ct = prefs[SessionKeys.AUTH_TOKEN_CIPHERTEXT] ?: return null
        val iv = prefs[SessionKeys.AUTH_TOKEN_IV] ?: return null
        return decrypt(unbase64(ct), unbase64(iv))
    }

    override suspend fun signupTimestamp(): Instant? =
        read(SessionKeys.SIGNUP_TS)?.let(Instant::parse)

    override suspend fun profile(): ParticipantProfile? {
        val prefs = context.sessionDataStore.data.first()
        val age = prefs[SessionKeys.PROFILE_AGE]?.toIntOrNull() ?: return null
        return ParticipantProfile(
            age = age,
            gender = prefs[SessionKeys.PROFILE_GENDER].orEmpty(),
            universityMajor = prefs[SessionKeys.PROFILE_MAJOR].orEmpty(),
            sasSvScore = prefs[SessionKeys.PROFILE_SAS_SV]?.toIntOrNull() ?: 0,
        )
    }

    override suspend fun hasCredentials(): Boolean = userId() != null

    override suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(AES_ALIAS)
    }

    private suspend fun read(key: androidx.datastore.preferences.core.Preferences.Key<String>): String? =
        context.sessionDataStore.data.first()[key]

    // --- crypto -----------------------------------------------------------

    private fun encrypt(plain: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return ciphertext to cipher.iv
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun aesKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(AES_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unbase64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_ALIAS = "auth_token_aes_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
