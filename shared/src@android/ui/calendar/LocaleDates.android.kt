package app.servicecalendar.ui.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.isoDayNumber
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

private fun calendarOf(year: Int, month: Int, day: Int) =
    GregorianCalendar().apply {
        clear()
        set(year, month, day)
    }

internal fun localMonthNameInternal(month: Month, locale: Locale): String =
    SimpleDateFormat("LLLL", locale).format(calendarOf(2000, month.ordinal, 1).time)

internal fun localWeekdayFullInternal(day: DayOfWeek, locale: Locale): String {
    // 2023-11-19 is a Sunday; offset by the Sunday-based day index.
    val reference = calendarOf(2023, Calendar.NOVEMBER, 19 + day.isoDayNumber % 7)
    return SimpleDateFormat("EEEE", locale).format(reference.time)
}

internal fun localWeekdayNarrowInternal(day: DayOfWeek, locale: Locale): String =
    localWeekdayFullInternal(day, locale).take(1).uppercase(locale)

internal fun localMediumDateInternal(date: LocalDate, locale: Locale): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
        .format(calendarOf(date.year, date.month.ordinal, date.day).time)

actual fun localMonthName(month: Month): String = localMonthNameInternal(month, Locale.getDefault())

actual fun localWeekdayNarrow(day: DayOfWeek): String = localWeekdayNarrowInternal(day, Locale.getDefault())

actual fun localWeekdayFull(day: DayOfWeek): String = localWeekdayFullInternal(day, Locale.getDefault())

actual fun localMediumDate(date: LocalDate): String = localMediumDateInternal(date, Locale.getDefault())
