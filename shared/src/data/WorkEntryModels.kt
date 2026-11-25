package app.servicecalendar.data

import app.servicecalendar.domain.completedCount
import app.servicecalendar.domain.percentDone
import app.servicecalendar.model.Place
import app.servicecalendar.money.MinorAmount
import kotlinx.datetime.LocalDate

data class DayEntryEditorData(
    val date: LocalDate,
    val place: Place,
    val generation: Long,
    val entryId: Long?,
    val rows: List<DayEntryEditorRow>,
    val otherPlacesTotal: MinorAmount,
    /** A: procedures scheduled for this day at this place; null when not entered. */
    val scheduled: Int? = null,
)

data class DayEntryEditorRow(
    val procedureId: Long,
    val procedureName: String,
    val procedureArchived: Boolean,
    val currentRate: MinorAmount?,
    val savedQuantity: Int?,
    val savedAppliedAmount: MinorAmount?,
) {
    init {
        require(currentRate != null || savedAppliedAmount != null) {
            "Editor row without a rate: procedure $procedureId"
        }
        require((savedQuantity == null) == (savedAppliedAmount == null)) {
            "Saved quantity and rate must be set together: procedure $procedureId"
        }
    }

    val price: MinorAmount
        get() = savedAppliedAmount ?: checkNotNull(currentRate)
}

/**
 * [entryId] is the entry that remains after the write: null when the entry was deleted (no lines and no scheduled count) or
 * never created. An entry kept only for its scheduled count (no lines) still returns its ID, with [placeTotal] zero.
 */
data class DayEntrySaveResult(
    val entryId: Long?,
    val placeTotal: MinorAmount,
    /** The scheduled count stored after the write. */
    val scheduled: Int? = null,
)

/** How [WorkEntryRepository.save] treats the stored scheduled count (A). */
sealed interface ScheduledChange {
    /** Leave the stored value as it is. */
    data object Keep : ScheduledChange

    /** Store [value]; null clears it. */
    data class Set(val value: Int?) : ScheduledChange
}

data class DayMark(
    val placeId: Long,
    val placeName: String,
    val color: Long,
)

data class DaySummary(
    val date: LocalDate,
    val places: List<PlaceDayCard>,
    val dayTotal: MinorAmount,
)

/** A card exists for every entry of the day, including one with only a scheduled count and no lines. */
data class PlaceDayCard(
    val entryId: Long,
    val place: Place,
    val lines: List<DayCardLine>,
    val placeTotal: MinorAmount,
    /** A: procedures scheduled; null when not entered. */
    val scheduled: Int? = null,
) {
    /** R: procedures done, the sum of line quantities. */
    val completed: Long
        get() = completedCount(lines.map { it.quantity })

    /** R / A × 100 rounded to one decimal; null when A is null or 0. */
    val percent: Double?
        get() = percentDone(completed, scheduled)
}

data class DayCardLine(
    val procedureId: Long,
    val procedureName: String,
    val procedureArchived: Boolean,
    val quantity: Int,
    val appliedAmount: MinorAmount,
    val lineTotal: MinorAmount,
)
