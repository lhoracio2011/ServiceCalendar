package app.servicecalendar.ui.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

internal object CalendarStrings {
    const val PREVIOUS_MONTH = "Previous month"
    const val NEXT_MONTH = "Next month"
    const val TODAY_DESCRIPTION = "Go to today"
    const val ADD = "Add"
    const val ARCHIVED_SUFFIX = " (archived)"
    const val OPEN_ENTRY = "Open entry"
    const val TODAY_MARK = "today"
    const val SELECTED_MARK = "selected"

    fun weekdayShort(day: DayOfWeek): String = localWeekdayNarrow(day)

    fun weekdayFull(day: DayOfWeek): String = localWeekdayFull(day)

    fun monthTitle(month: YearMonth): String = "${localMonthName(month.month)} ${month.year}"

    fun dateText(date: LocalDate): String = localMediumDate(date)

    fun dayHeading(date: LocalDate): String = weekdayFull(date.dayOfWeek) + ", " + dateText(date)

    fun recordsCount(count: Int): String = when (count) {
        0 -> "no entries"
        1 -> "1 entry"
        else -> "$count entries"
    }

    fun dayCellDescription(
        date: LocalDate,
        recordCount: Int?,
        placeNames: List<String>,
        isToday: Boolean,
        isSelected: Boolean,
    ): String = buildString {
        append(dateText(date))
        if (isToday) append(", ").append(TODAY_MARK)
        if (isSelected) append(", ").append(SELECTED_MARK)
        if (recordCount != null) {
            append(", ").append(recordsCount(recordCount))
            if (placeNames.isNotEmpty()) append(": ").append(placeNames.joinToString(", "))
        }
    }

    fun marksDescription(placeNames: List<String>): String = "Places: " + placeNames.joinToString(", ")

    fun cardDate(date: LocalDate): String =
        "(" + date.day.toString().padStart(2, '0') + "/" + (date.month.ordinal + 1).toString().padStart(2, '0') + ")"

    const val LABEL_SCHEDULED = "A"
    const val LABEL_COMPLETED = "R"
    const val LABEL_PERCENT = "%"
    const val SCHEDULED_DIALOG_TITLE = "Scheduled procedures (A)"
    const val SCHEDULED_FIELD = "Scheduled procedures"
    const val SCHEDULED_SAVE = "Save"
    const val SCHEDULED_CANCEL = "Cancel"
    const val NOT_ENTERED = "not entered"
    const val NOT_AVAILABLE = "not available"
    const val SCHEDULED_STALE_TEXT =
        "The local data changed (for example, after a backup restore), so the number was not saved. " +
            "The current data is shown; enter the number again."

    fun cardDescription(placeName: String, total: String): String =
        "$placeName, total $total. $OPEN_ENTRY"

    fun scheduledDescription(value: String?): String =
        "Scheduled procedures (A): ${value ?: NOT_ENTERED}. Tap to change"

    fun completedDescription(value: String): String = "Procedures done (R): $value"

    fun percentDescription(value: String): String = "Percent done: $value"

    fun lineText(procedureName: String, quantity: Int): String = "$procedureName × $quantity"
}
