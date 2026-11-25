package app.servicecalendar.ui.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.isoDayNumber
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSDateComponents
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.preferredLanguages

private fun formatterOf(locale: NSLocale) = NSDateFormatter().apply { this.locale = locale }

// Symbol arrays are Sunday-first, so Sunday is index 0.
private fun DayOfWeek.sundayFirstIndex() = isoDayNumber % 7

internal fun localMonthNameInternal(month: Month, locale: NSLocale): String =
    formatterOf(locale).standaloneMonthSymbols[month.ordinal] as String

internal fun localWeekdayNarrowInternal(day: DayOfWeek, locale: NSLocale): String =
    (formatterOf(locale).veryShortStandaloneWeekdaySymbols[day.sundayFirstIndex()] as String).uppercase()

internal fun localWeekdayFullInternal(day: DayOfWeek, locale: NSLocale): String =
    formatterOf(locale).standaloneWeekdaySymbols[day.sundayFirstIndex()] as String

internal fun localMediumDateInternal(date: LocalDate, locale: NSLocale): String {
    val calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)
    val components = NSDateComponents().apply {
        year = date.year.toLong()
        month = (date.month.ordinal + 1).toLong()
        day = date.day.toLong()
    }
    val formatter = formatterOf(locale).apply {
        this.calendar = calendar
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterNoStyle
    }
    return formatter.stringFromDate(calendar.dateFromComponents(components)!!)
}

/**
 * Locale for user-visible date names. `NSLocale.currentLocale` is restricted to the
 * bundle's localizations (only `en`), so it would keep names English on a Portuguese
 * phone. Like Android's `Locale.getDefault()`, follow the user's first preferred
 * system language (e.g. "pt-BR") instead, falling back to the current locale.
 */
private fun userLocale(): NSLocale =
    (NSLocale.preferredLanguages.firstOrNull() as? String)
        ?.takeIf { it.isNotBlank() }
        ?.let { NSLocale(localeIdentifier = it) }
        ?: NSLocale.currentLocale

actual fun localMonthName(month: Month): String = localMonthNameInternal(month, userLocale())

actual fun localWeekdayNarrow(day: DayOfWeek): String = localWeekdayNarrowInternal(day, userLocale())

actual fun localWeekdayFull(day: DayOfWeek): String = localWeekdayFullInternal(day, userLocale())

actual fun localMediumDate(date: LocalDate): String = localMediumDateInternal(date, userLocale())
