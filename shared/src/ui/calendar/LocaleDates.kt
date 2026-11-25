package app.servicecalendar.ui.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month

expect fun localMonthName(month: Month): String
expect fun localWeekdayNarrow(day: DayOfWeek): String
expect fun localWeekdayFull(day: DayOfWeek): String
expect fun localMediumDate(date: LocalDate): String