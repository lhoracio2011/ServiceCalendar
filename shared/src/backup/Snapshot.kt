package app.servicecalendar.backup

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class BackupSnapshot(
    val schemaVersion: Int,
    val createdAt: String,
    val currencyCode: String,
    val procedures: List<ProcedureDto>,
    val places: List<PlaceDto>,
    val placeRates: List<PlaceRateDto>,
    val workEntries: List<WorkEntryDto>,
    val workLines: List<WorkLineDto>,
) {
    companion object {
        /** 2 adds [WorkEntryDto.scheduledCount]; version 1 files are still accepted and read with it absent (null). */
        const val CURRENT_VERSION: Int = 2

        const val MIN_SUPPORTED_VERSION: Int = 1
    }
}

@Serializable
data class ProcedureDto(
    val id: Long,
    val name: String,
    val archived: Boolean,
)

@Serializable
data class PlaceDto(
    val id: Long,
    val name: String,
    val address: String? = null,
    val color: Long,
    val archived: Boolean,
)

@Serializable
data class PlaceRateDto(
    val placeId: Long,
    val procedureId: Long,
    val amountMinor: Long,
)

@Serializable
data class WorkEntryDto(
    val id: Long,
    val localDate: String,
    val placeId: Long,
    /** Procedures scheduled for the day (0..Int.MAX) or null when not entered; absent in version 1. Long so the range is checked by the validator. */
    val scheduledCount: Long? = null,
)

@Serializable
data class WorkLineDto(
    val entryId: Long,
    val procedureId: Long,
    val quantity: Long,
    val appliedAmountMinor: Long,
)

fun utcTimestamp(instant: Instant = Clock.System.now()): String =
    Instant.fromEpochSeconds(instant.epochSeconds).toString()
