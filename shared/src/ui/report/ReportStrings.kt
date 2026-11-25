package app.servicecalendar.ui.report

import app.servicecalendar.money.NO_PERCENT
import app.servicecalendar.ui.calendar.CalendarStrings
import kotlinx.datetime.LocalDate

internal object ReportStrings {
    const val FROM_LABEL = "From"
    const val TO_LABEL = "To"
    const val LOADING = "Loading…"
    const val EMPTY = "No entries for this period"
    const val EMPTY_HINT = "Change the dates or add entries in the calendar."
    const val TOTAL = "Total"
    const val ARCHIVED_SUFFIX = " (archived)"
    const val PICK_FROM_TITLE = "Start Date"
    const val PICK_TO_TITLE = "End Date"
    const val PICK_CONFIRM = "OK"
    const val PICK_CANCEL = "Cancel"

    const val ERROR_PERIOD = "The end date is before the start date. Change one of the dates."
    const val ERROR_AMOUNT = "The total for this period is too large to display."
    const val ERROR_UNAVAILABLE = "Could not read the data. Please try again."

    fun errorText(reason: ReportError): String = when (reason) {
        ReportError.InvalidPeriod -> ERROR_PERIOD
        ReportError.AmountTooLarge -> ERROR_AMOUNT
        ReportError.Unavailable -> ERROR_UNAVAILABLE
    }

    fun dateText(date: LocalDate): String = CalendarStrings.dateText(date)

    fun fromDescription(date: LocalDate): String = "Start date: ${dateText(date)}. Tap to change"

    fun toDescription(date: LocalDate): String = "End date: ${dateText(date)}. Tap to change"

    fun daysCount(count: Int): String = if (count == 1) "1 day" else "$count days"

    fun entryDaysText(count: Int): String = "Days with entries: $count"

    fun rowDescription(placeName: String, archived: Boolean, total: String, entryDays: Int, stats: String): String =
        placeName + (if (archived) ARCHIVED_SUFFIX else "") + ", total $total, " + daysCount(entryDays) + " with entries. $stats"

    fun totalDescription(total: String, stats: String): String = "Total for the period: $total. $stats"

    fun procedureLine(name: String, archived: Boolean, quantity: Long): String =
        "$name" + (if (archived) ARCHIVED_SUFFIX else "") + " × $quantity"

    /** Visible stats line: "A 110 · R 67 · % 60,9". */
    fun statsLine(scheduled: Long?, completed: Long, percent: String): String =
        "A ${scheduled ?: NO_PERCENT} · R $completed · % $percent"

    fun statsDescription(scheduled: Long?, completed: Long, percent: String?): String =
        "Scheduled ${scheduled ?: "not entered"}, done $completed, percent " + (percent ?: "not available")

    fun procedureDescription(name: String, archived: Boolean, quantity: Long, total: String): String =
        name + (if (archived) ARCHIVED_SUFFIX else "") + ", $quantity, $total"

    fun periodHeading(from: LocalDate, to: LocalDate): String = "${dateText(from)} – ${dateText(to)}"
}
