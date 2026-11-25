package app.servicecalendar.model

import app.servicecalendar.money.MinorAmount
import kotlinx.datetime.LocalDate

data class Procedure(
    val id: Long,
    val name: String,
    val archived: Boolean = false,
)

data class Place(
    val id: Long,
    val name: String,
    val address: String?,
    val color: Long,
    val archived: Boolean = false,
)

data class PlaceRate(
    val placeId: Long,
    val procedureId: Long,
    val amountMinor: MinorAmount,
)

data class WorkEntry(
    val id: Long,
    val localDate: LocalDate,
    val placeId: Long,
)

data class WorkLine(
    val entryId: Long,
    val procedureId: Long,
    val quantity: Int,
    val appliedAmountMinor: MinorAmount,
)

data class DatasetSettings(
    val currencyCode: String,
)
