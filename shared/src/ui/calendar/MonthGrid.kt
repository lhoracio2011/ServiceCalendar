package app.servicecalendar.ui.calendar

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus
import kotlinx.datetime.plusMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.yearMonth

data class GridCell(val date: LocalDate, val inMonth: Boolean)

data class MonthGrid(
    val month: YearMonth,
    val firstDayOfWeek: DayOfWeek,
    val weeks: List<List<GridCell>>,
) {
    val cells: List<GridCell> get() = weeks.flatten()
}

fun weekdayOrder(firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY): List<DayOfWeek> =
    List(DAYS_IN_WEEK) { DayOfWeek.entries[(firstDayOfWeek.ordinal + it) % DAYS_IN_WEEK] }

fun buildMonthGrid(
    month: YearMonth,
    firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
    minWeeks: Int = 0,
): MonthGrid {
    val first = month.firstDay
    val leading = (first.dayOfWeek.ordinal - firstDayOfWeek.ordinal + DAYS_IN_WEEK) % DAYS_IN_WEEK
    val weekCount = maxOf(minWeeks, (leading + month.numberOfDays + DAYS_IN_WEEK - 1) / DAYS_IN_WEEK)
    val start = first.plus(DatePeriod(days = -leading))
    val weeks = List(weekCount) { week ->
        List(DAYS_IN_WEEK) { dayInWeek ->
            val date = start.plus(DatePeriod(days = week * DAYS_IN_WEEK + dayInWeek))
            GridCell(date, inMonth = date.yearMonth == month)
        }
    }
    return MonthGrid(month, firstDayOfWeek, weeks)
}

data class CalendarPosition(val month: YearMonth, val selected: LocalDate) {
    init {
        require(selected.yearMonth == month) { "Selected date $selected is outside month $month" }
    }

    val canGoPrevious: Boolean get() = month.year > MIN_NAVIGABLE_YEAR || month.month.ordinal > 0

    val canGoNext: Boolean get() = month.year < MAX_NAVIGABLE_YEAR || month.month.ordinal < 11

    fun previousMonth(): CalendarPosition = if (canGoPrevious) moveTo(month.minusMonth()) else this

    fun nextMonth(): CalendarPosition = if (canGoNext) moveTo(month.plusMonth()) else this

    fun select(date: LocalDate): CalendarPosition = CalendarPosition(date.yearMonth, date)

    fun goToToday(today: LocalDate): CalendarPosition = select(today)

    private fun moveTo(newMonth: YearMonth): CalendarPosition =
        CalendarPosition(newMonth, clampToMonth(selected, newMonth))
}

fun initialPosition(today: LocalDate): CalendarPosition = CalendarPosition(today.yearMonth, today)

fun clampToMonth(selected: LocalDate, month: YearMonth): LocalDate =
    LocalDate(month.year, month.month, minOf(selected.day, month.numberOfDays))

private const val DAYS_IN_WEEK = 7
private const val MIN_NAVIGABLE_YEAR = 1
private const val MAX_NAVIGABLE_YEAR = 9998
