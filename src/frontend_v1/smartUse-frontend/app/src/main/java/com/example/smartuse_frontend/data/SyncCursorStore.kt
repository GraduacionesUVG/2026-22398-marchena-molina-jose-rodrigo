package com.example.smartuse_frontend.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The canonical stream's server-confirmed cursor (`synced_through`). */
data class CanonicalSyncCursor(val startTs: Long, val epochId: String)

interface SyncCursorStore {
    suspend fun getCanonicalSyncedThrough(): CanonicalSyncCursor?
    suspend fun setCanonicalSyncedThrough(cursor: CanonicalSyncCursor)

    /** Forget the local marker — used when the server reports nothing synced for this subject. */
    suspend fun clearCanonicalSyncedThrough()

    suspend fun getLastSuccessfulCanonicalSyncAt(): Instant?
    suspend fun setLastSuccessfulCanonicalSyncAt(instant: Instant)

    /** Wipes the sync watermark once opt-out is confirmed. */
    suspend fun clear()
}

private val cursorDisplayFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

/** Participant-adjacent debug text: local date-time of the cursor's epoch plus a short id. */
fun CanonicalSyncCursor.describe(zone: ZoneId = ZoneId.systemDefault()): String =
    "${cursorDisplayFormat.format(Instant.ofEpochMilli(startTs).atZone(zone))} (id ${epochId.take(8)})"

fun describeCursor(cursor: CanonicalSyncCursor?, zone: ZoneId = ZoneId.systemDefault()): String =
    cursor?.describe(zone) ?: "nada sincronizado aún"
