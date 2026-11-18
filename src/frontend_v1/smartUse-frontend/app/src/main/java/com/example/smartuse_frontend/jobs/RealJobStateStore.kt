package com.example.smartuse_frontend.jobs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.smartuse_frontend.data.OptOutKind
import com.example.smartuse_frontend.network.FailureKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

private val Context.jobStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "job_state")

private class JobKeys(job: JobName) {
    private val p = job.key
    val lastAttemptAt = stringPreferencesKey("${p}_last_attempt_at")
    val lastSuccessAt = stringPreferencesKey("${p}_last_success_at")
    val consecutiveFailedRuns = intPreferencesKey("${p}_consecutive_failed_runs")
    val firstFailureAt = stringPreferencesKey("${p}_first_failure_at")
    val failureKind = stringPreferencesKey("${p}_failure_kind")
    val failureMessage = stringPreferencesKey("${p}_failure_message")
    val standbyBucket = intPreferencesKey("${p}_standby_bucket")
}

private val OPTED_OUT_AT = stringPreferencesKey("opted_out_at")
private val OPT_OUT_KIND = stringPreferencesKey("opt_out_kind")
private val HARD_DELETE_CONFIRMED = booleanPreferencesKey("hard_delete_confirmed")
private val CUTOFF_DATE = stringPreferencesKey("cutoff_date")                 // ISO yyyy-MM-dd
private val COLLECTION_ENDED_AT = stringPreferencesKey("collection_ended_at") // ISO instant
// Written by the removed permission watcher; still cleared so an old build leaves nothing behind.
private val LEGACY_NOTIFIED_PERMISSION_LAPSES = stringSetPreferencesKey("notified_permission_lapses")

class RealJobStateStore(private val context: Context) : JobStateStore {

    override suspend fun get(job: JobName): JobState {
        val prefs = context.jobStateDataStore.data.first()
        val k = JobKeys(job)
        val kind = prefs[k.failureKind]?.let { runCatching { FailureKind.valueOf(it) }.getOrNull() }
        return JobState(
            lastAttemptAt = prefs[k.lastAttemptAt]?.let(Instant::parse),
            lastSuccessAt = prefs[k.lastSuccessAt]?.let(Instant::parse),
            consecutiveFailedRuns = prefs[k.consecutiveFailedRuns] ?: 0,
            firstFailureAt = prefs[k.firstFailureAt]?.let(Instant::parse),
            lastFailure = kind?.let { JobFailure(it, prefs[k.failureMessage].orEmpty()) },
            lastStandbyBucket = prefs[k.standbyBucket],
        )
    }

    override suspend fun put(job: JobName, state: JobState) {
        val k = JobKeys(job)
        context.jobStateDataStore.edit { prefs ->
            prefs.setOrRemove(k.lastAttemptAt, state.lastAttemptAt?.toString())
            prefs.setOrRemove(k.lastSuccessAt, state.lastSuccessAt?.toString())
            prefs[k.consecutiveFailedRuns] = state.consecutiveFailedRuns
            prefs.setOrRemove(k.firstFailureAt, state.firstFailureAt?.toString())
            prefs.setOrRemove(k.failureKind, state.lastFailure?.kind?.name)
            prefs.setOrRemove(k.failureMessage, state.lastFailure?.message)
            prefs.setOrRemove(k.standbyBucket, state.lastStandbyBucket)
        }
    }

    override suspend fun optedOutAt(): Instant? =
        context.jobStateDataStore.data.first()[OPTED_OUT_AT]?.let(Instant::parse)

    override suspend fun setOptedOutAt(instant: Instant) {
        context.jobStateDataStore.edit { it[OPTED_OUT_AT] = instant.toString() }
    }

    override val hasOptedOut: Flow<Boolean> =
        context.jobStateDataStore.data.map { it[OPTED_OUT_AT] != null }

    override suspend fun optOutKind(): OptOutKind? =
        context.jobStateDataStore.data.first()[OPT_OUT_KIND]
            ?.let { name -> runCatching { OptOutKind.valueOf(name) }.getOrNull() }

    override suspend fun setOptOutKind(kind: OptOutKind) {
        context.jobStateDataStore.edit { it[OPT_OUT_KIND] = kind.name }
    }

    override suspend fun hardDeleteConfirmed(): Boolean =
        context.jobStateDataStore.data.first()[HARD_DELETE_CONFIRMED] ?: false

    override suspend fun setHardDeleteConfirmed(confirmed: Boolean) {
        context.jobStateDataStore.edit { it[HARD_DELETE_CONFIRMED] = confirmed }
    }

    override suspend fun cutoffDate(): LocalDate? =
        context.jobStateDataStore.data.first()[CUTOFF_DATE]
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    override suspend fun setCutoffDate(date: LocalDate?) {
        context.jobStateDataStore.edit { it.setOrRemove(CUTOFF_DATE, date?.toString()) }
    }

    override suspend fun collectionEndedAt(): Instant? =
        parseInstantOrNull(context.jobStateDataStore.data.first()[COLLECTION_ENDED_AT])

    override suspend fun setCollectionEndedAt(instant: Instant) {
        context.jobStateDataStore.edit { it[COLLECTION_ENDED_AT] = instant.toString() }
    }

    override val optOutRecord: Flow<OptOutRecord> = context.jobStateDataStore.data.map { prefs ->
        OptOutRecord(
            optedOutAt = prefs[OPTED_OUT_AT]?.let(Instant::parse),
            kind = prefs[OPT_OUT_KIND]?.let { name -> runCatching { OptOutKind.valueOf(name) }.getOrNull() },
            hardDeleteConfirmed = prefs[HARD_DELETE_CONFIRMED] ?: false,
        )
    }

    override val collectionEnded: Flow<Boolean> =
        context.jobStateDataStore.data.map { it[COLLECTION_ENDED_AT] != null }

    override suspend fun clearJobHistoryAndNotifications() {
        context.jobStateDataStore.edit { prefs ->
            JobName.entries.forEach { job ->
                val k = JobKeys(job)
                prefs.remove(k.lastAttemptAt)
                prefs.remove(k.lastSuccessAt)
                prefs.remove(k.consecutiveFailedRuns)
                prefs.remove(k.firstFailureAt)
                prefs.remove(k.failureKind)
                prefs.remove(k.failureMessage)
                prefs.remove(k.standbyBucket)
            }
            prefs.remove(LEGACY_NOTIFIED_PERMISSION_LAPSES)
        }
    }
}

private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
    if (value == null) remove(key) else this[key] = value
}

/** A corrupt stored value reads as unset rather than crashing the gate. */
internal fun parseInstantOrNull(value: String?): Instant? =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() }
