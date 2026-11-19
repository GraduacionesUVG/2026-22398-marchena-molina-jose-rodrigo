package com.example.smartuse_frontend.data

import android.content.Context
import android.util.Log
import com.example.smartuse_frontend.jobs.JobStateStore
import com.example.smartuse_frontend.jobs.RealJobStateStore
import kotlinx.coroutines.CancellationException

private const val TAG = "OptOut"

/**
 * Best-effort local cleanup, run once opt-out is confirmed with the backend
 * (immediately for soft in the common case; possibly much later for hard, if
 * the delete call needed retries). Never a precondition for the opt-out
 * outcome itself — the terminal screen already shows before this runs. Each
 * step is independent: one failing (e.g. a Keystore alias already gone) must
 * not skip the rest.
 */
class LocalDataWiper(
    private val credentialStore: CredentialStore,
    private val syncCursorStore: SyncCursorStore,
    private val jobStateStore: JobStateStore,
    private val keystoreKeyManager: KeystoreKeyManager,
    private val deleteDatabases: () -> Unit,
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
) {
    suspend fun wipe() {
        step("credentials") { credentialStore.clear() }
        step("sync cursor") { syncCursorStore.clear() }
        step("job history") { jobStateStore.clearJobHistoryAndNotifications() }
        step("HMAC key") { keystoreKeyManager.destroyKeys() }
        step("local databases") { deleteDatabases() }
    }

    private suspend fun step(name: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("wipe: $name failed: ${e::class.java.simpleName}: ${e.message}")
        }
    }
}

/** The real wiper: opt-out (app and worker) and the end of collection all erase the same things. */
fun realLocalDataWiper(
    context: Context,
    jobStateStore: JobStateStore = RealJobStateStore(context),
): LocalDataWiper = LocalDataWiper(
    credentialStore = RealCredentialStore(context),
    syncCursorStore = RealSyncCursorStore(context),
    jobStateStore = jobStateStore,
    keystoreKeyManager = RealKeystoreKeyManager(),
    deleteDatabases = {
        context.deleteDatabase("canonical.db")
        context.deleteDatabase("raw_capture.db")
    },
)
