package com.example.smartuse_frontend.study

import android.util.Log
import com.example.smartuse_frontend.jobs.JobStateStore
import com.example.smartuse_frontend.network.NetworkResult
import com.example.smartuse_frontend.network.SmartUseApi
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

private const val TAG = "Cutoff"

/**
 * Keeps the stored cutoff in step with GET /cutoff. Called at the start of
 * every sync run and on every app launch. `null` clears the stored date; a
 * failed fetch or an unparseable date keeps it, so the phone keeps working
 * offline. Nothing is fetched after an opt-out or the end. Never throws.
 */
class CutoffRefresher(
    private val api: SmartUseApi,
    private val stateStore: JobStateStore,
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
) {
    /** True only when the stored date changed. Never throws. */
    suspend fun refresh(): Boolean {
        return try {
            if (stateStore.optedOutAt() != null || stateStore.collectionEndedAt() != null) return false
            when (val result = api.getCutoff()) {
                is NetworkResult.Success -> store(result.body.cutoff)
                else -> {
                    logger("cutoff: fetch failed ($result), keeping the stored value")
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("cutoff: refresh failed: ${e::class.java.simpleName}: ${e.message}")
            false
        }
    }

    private suspend fun store(raw: String?): Boolean {
        val date = if (raw == null) {
            null
        } else {
            runCatching { LocalDate.parse(raw) }.getOrNull() ?: run {
                logger("cutoff: unparseable date '$raw', keeping the stored value")
                return false
            }
        }
        if (date == stateStore.cutoffDate()) return false
        stateStore.setCutoffDate(date)
        logger("cutoff: ${date ?: "none published"}")
        return true
    }
}
