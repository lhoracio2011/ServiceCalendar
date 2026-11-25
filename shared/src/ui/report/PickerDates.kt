package app.servicecalendar.ui.report

import kotlinx.datetime.LocalDate

private const val MILLIS_PER_DAY = 86_400_000L

/** The Material3 date picker exchanges dates as UTC-midnight milliseconds; convert via epoch days to avoid time-zone shifts. */
internal fun LocalDate.toPickerMillis(): Long = toEpochDays() * MILLIS_PER_DAY

internal fun pickerMillisToDate(millis: Long): LocalDate =
    LocalDate.fromEpochDays(millis.floorDiv(MILLIS_PER_DAY))
