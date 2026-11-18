package com.example.smartuse_frontend.network.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The backend's `start_ts` is an ISO-8601 `date-time` string; on-device it is
 * epoch millis. Serialization converts at the wire boundary so nothing else
 * has to know. Decoding goes through [OffsetDateTime] so both `...Z` and
 * `...+00:00` (and microsecond precision) are accepted.
 */
object EpochMillisAsIsoSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("EpochMillisAsIso", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(Instant.ofEpochMilli(value).toString())
    }

    override fun deserialize(decoder: Decoder): Long {
        val text = decoder.decodeString()
        return try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (e: DateTimeException) {
            throw SerializationException("Not an ISO-8601 date-time: '$text'", e)
        }
    }
}

// No end_ts/duration_s — the backend derives those server-side. The epoch id
// is deterministic (UUIDv5 of the epoch's inputs), so a resend is idempotent.
// `granularity` is deliberately not defaulted: appJson has
// encodeDefaults = false, which would silently drop a defaulted value.
@Serializable
data class CanonicalRecordDto(
    @SerialName("client_record_id") val clientRecordId: String,
    @SerialName("start_ts")
    @Serializable(with = EpochMillisAsIsoSerializer::class)
    val startTs: Long,
    @SerialName("is_use") val isUse: Boolean,
    @SerialName("distinct_app_count") val distinctAppCount: Int,
    val granularity: String,
)

@Serializable
data class CanonicalSyncRequest(val records: List<CanonicalRecordDto>)

@Serializable
data class SyncedThroughDto(
    @SerialName("start_ts")
    @Serializable(with = EpochMillisAsIsoSerializer::class)
    val startTs: Long,
    @SerialName("client_record_id") val clientRecordId: String,
)

@Serializable
data class CanonicalSyncResponse(
    val received: Int = 0,
    @SerialName("synced_through") val syncedThrough: SyncedThroughDto? = null,
)

/** GET /sync/canonical/cursor — flat (not nested under synced_through); both fields null if nothing synced yet. */
@Serializable
data class CanonicalCursorResponse(
    @SerialName("client_record_id") val clientRecordId: String? = null,
    @SerialName("start_ts")
    @Serializable(with = EpochMillisAsIsoSerializer::class)
    val startTs: Long? = null,
)
