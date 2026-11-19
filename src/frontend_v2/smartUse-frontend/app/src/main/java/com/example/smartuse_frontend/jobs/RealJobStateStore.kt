package com.example.smartuse_frontend.jobs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.smartuse_frontend.inference.FiringState
import com.example.smartuse_frontend.network.FailureKind
import com.example.smartuse_frontend.study.EndMarks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
private val CUTOFF_DATE = stringPreferencesKey("cutoff_date")                 // ISO yyyy-MM-dd
private val COLLECTION_ENDED_AT = stringPreferencesKey("collection_ended_at") // ISO instant
// The execution rules' control state.
private val FIRING_LAST_SHOWN_F = stringPreferencesKey("firing_last_shown_f") // ISO instant
private val FIRING_LAST_SHOWN_S = stringPreferencesKey("firing_last_shown_s") // ISO instant
private val FIRING_SHOWN_DAY = stringPreferencesKey("firing_shown_day")       // ISO yyyy-MM-dd
private val FIRING_SHOWN_TODAY = intPreferencesKey("firing_shown_today")
private val FORMS_COMPLETED_AT = stringPreferencesKey("forms_completed_at")   // ISO instant
private val FINAL_SENT_AT = stringPreferencesKey("final_sent_at")             // ISO instant
private val END_CLEANUP_DONE_AT = stringPreferencesKey("end_cleanup_done_at") // ISO instant

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


    override suspend fun formsCompletedAt(): Instant? =
        parseInstantOrNull(context.jobStateDataStore.data.first()[FORMS_COMPLETED_AT])

    override suspend fun setFormsCompletedAt(instant: Instant) {
        context.jobStateDataStore.edit { it[FORMS_COMPLETED_AT] = instant.toString() }
    }

    override suspend fun finalSentAt(): Instant? =
        parseInstantOrNull(context.jobStateDataStore.data.first()[FINAL_SENT_AT])

    override suspend fun setFinalSentAt(instant: Instant) {
        context.jobStateDataStore.edit { it[FINAL_SENT_AT] = instant.toString() }
    }

    override suspend fun endCleanupDoneAt(): Instant? =
        parseInstantOrNull(context.jobStateDataStore.data.first()[END_CLEANUP_DONE_AT])

    override suspend fun setEndCleanupDoneAt(instant: Instant) {
        context.jobStateDataStore.edit { it[END_CLEANUP_DONE_AT] = instant.toString() }
    }

    override suspend fun readEndMarks(): EndMarks = context.jobStateDataStore.data.first().toEndMarks()

    override val endMarks: Flow<EndMarks> =
        context.jobStateDataStore.data.map { it.toEndMarks() }.distinctUntilChanged()

    override suspend fun firingState(): FiringState {
        val prefs = context.jobStateDataStore.data.first()
        return FiringState(
            lastShownF = parseInstantOrNull(prefs[FIRING_LAST_SHOWN_F]),
            lastShownS = parseInstantOrNull(prefs[FIRING_LAST_SHOWN_S]),
            shownDay = prefs[FIRING_SHOWN_DAY]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            shownToday = prefs[FIRING_SHOWN_TODAY] ?: 0,
        )
    }

    override suspend fun setFiringState(state: FiringState) {
        context.jobStateDataStore.edit { prefs ->
            prefs.setOrRemove(FIRING_LAST_SHOWN_F, state.lastShownF?.toString())
            prefs.setOrRemove(FIRING_LAST_SHOWN_S, state.lastShownS?.toString())
            prefs.setOrRemove(FIRING_SHOWN_DAY, state.shownDay?.toString())
            prefs[FIRING_SHOWN_TODAY] = state.shownToday
        }
    }

}

private fun Preferences.toEndMarks() = EndMarks(
    collectionEndedAt = parseInstantOrNull(this[COLLECTION_ENDED_AT]),
    optedOutAt = parseInstantOrNull(this[OPTED_OUT_AT]),
    formsCompletedAt = parseInstantOrNull(this[FORMS_COMPLETED_AT]),
    finalSentAt = parseInstantOrNull(this[FINAL_SENT_AT]),
)

private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
    if (value == null) remove(key) else this[key] = value
}

/** A corrupt stored value reads as unset rather than crashing the gate. */
internal fun parseInstantOrNull(value: String?): Instant? =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() }
