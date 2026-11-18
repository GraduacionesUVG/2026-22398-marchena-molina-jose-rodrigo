package com.example.smartuse_frontend.data

import android.content.Context
import android.util.Log
import com.example.smartuse_frontend.data.db.RAW_LOG_EXPORT_FILE
import kotlinx.coroutines.CancellationException
import java.io.File

private const val TAG = "LocalDataWiper"

/** The usage data the end erases: raw log and canonical epochs (+ cursor). The model profile joins when it exists. */
internal val USAGE_DATABASES = listOf("canonical.db", "raw_capture.db")

/** Copies of the usage data in the cache: the Data page's raw-log export. */
internal val USAGE_CACHE_FILES = listOf(RAW_LOG_EXPORT_FILE)

/**
 * Erases the participant's usage data from the device, best-effort. Never
 * touches credentials, Keystore keys, job state or performance metrics
 * (`metrics.db`): metrics stay until the app is uninstalled. EndingCleanup runs it after the
 * confirmed final send.
 */
class LocalDataWiper(
    private val deleteDatabases: () -> Unit,
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
) {
    suspend fun wipe() {
        try {
            deleteDatabases()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("wipe: usage databases failed: ${e::class.java.simpleName}: ${e.message}")
        }
    }
}

fun realLocalDataWiper(context: Context): LocalDataWiper = LocalDataWiper(
    deleteDatabases = {
        USAGE_DATABASES.forEach { context.deleteDatabase(it) }
        USAGE_CACHE_FILES.forEach { File(context.cacheDir, it).delete() }
    },
)
