package app.servicecalendar.ui.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The month grid and month switching, tested without Compose, a database or the system clock. */
class MonthGridTest {
    private fun grid(year: Int, month: Int, first: DayOfWeek = DayOfWeek.MONDAY) =
        buildMonthGrid(YearMonth(year, month), first)

    private fun MonthGrid.inMonthDates(): List<LocalDate> = cells.filter { it.inMonth }.map { it.date }

    @Test
    fun `month lengths are 28 29 30 and 31 days`() {
        assertEquals(28, grid(2026, 2).inMonthDates().size)
        assertEquals(29, grid(2028, 2).inMonthDates().size)
        assertEquals(30, grid(2026, 11).inMonthDates().size)
        assertEquals(31, grid(2026, 12).inMonthDates().size)
    }

    @Test
    fun `every month starting on each weekday builds whole weeks starting on the first day of week`() {
        // One month for each weekday the first day can fall on (2026-06-01 is a Monday, 2026-02-01 a Sunday).
        val starts = mapOf(
            DayOfWeek.MONDAY to YearMonth(2026, 6),
            DayOfWeek.TUESDAY to YearMonth(2026, 9),
            DayOfWeek.WEDNESDAY to YearMonth(2026, 4),
            DayOfWeek.THURSDAY to YearMonth(2026, 1),
            DayOfWeek.FRIDAY to YearMonth(2026, 5),
            DayOfWeek.SATURDAY to YearMonth(2026, 8),
            DayOfWeek.SUNDAY to YearMonth(2026, 2),
        )
        for ((weekday, month) in starts) {
            assertEquals(weekday, month.firstDay.dayOfWeek, "fixture check for $month")
            for (first in DayOfWeek.entries) {
                val g = buildMonthGrid(month, first)
                assertEquals(0, g.cells.size % 7)
                assertTrue(g.weeks.size in 4..6, "${g.weeks.size} rows for $month")
                assertTrue(g.weeks.all { it.size == 7 })
                assertEquals(first, g.cells.first().date.dayOfWeek)
                assertEquals(month.numberOfDays, g.inMonthDates().size)
                // the first day of the month sits in the column that matches its weekday
                val column = g.cells.indexOfFirst { it.date == month.firstDay } % 7
                assertEquals(weekdayOrder(first)[column], weekday)
            }
        }
    }

    @Test
    fun `cells are consecutive days and flags match the month`() {
        val g = grid(2026, 11)
        g.cells.zipWithNext().forEach { (a, b) -> assertEquals(a.date.toEpochDays() + 1, b.date.toEpochDays()) }
        g.cells.forEach { assertEquals(it.date.month == Month.NOVEMBER && it.date.year == 2026, it.inMonth) }
    }

    @Test
    fun `november 2026 matches the reference with monday start`() {
        // 1 November 2026 is a Sunday: six leading October days, 30 days, six December days, so 6 weeks.
        val g = grid(2026, 11)
        assertEquals(6, g.weeks.size)
        assertEquals(LocalDate(2026, 10, 26), g.cells.first().date)
        assertEquals(LocalDate(2026, 12, 6), g.cells.last().date)
        assertEquals(6, g.cells.takeWhile { !it.inMonth }.size)
    }

    @Test
    fun `sunday first layout of november 2026 starts on the first and takes five rows`() {
        val g = grid(2026, 11, DayOfWeek.SUNDAY)
        assertEquals(5, g.weeks.size)
        assertEquals(LocalDate(2026, 11, 1), g.cells.first().date)
        assertTrue(g.cells.first().inMonth)
        assertEquals(LocalDate(2026, 12, 5), g.cells.last().date)
    }

    @Test
    fun `february 2027 starting on monday takes exactly four rows with no neighbour days`() {
        val g = grid(2027, 2)
        assertEquals(4, g.weeks.size)
        assertTrue(g.cells.all { it.inMonth })
    }

    @Test
    fun `weekday order rotates from the chosen first day`() {
        assertEquals(
            listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
            ),
            weekdayOrder(DayOfWeek.MONDAY),
        )
        assertEquals(DayOfWeek.SUNDAY, weekdayOrder().first())
        assertEquals(DayOfWeek.SATURDAY, weekdayOrder().last())
        assertEquals(DayOfWeek.SUNDAY, weekdayOrder(DayOfWeek.SUNDAY).first())
        assertEquals(DayOfWeek.SATURDAY, weekdayOrder(DayOfWeek.SUNDAY).last())
    }

    @Test
    fun `next month from december crosses the year boundary`() {
        val p = CalendarPosition(YearMonth(2026, 12), LocalDate(2026, 12, 15)).nextMonth()
        assertEquals(YearMonth(2027, 1), p.month)
        assertEquals(LocalDate(2027, 1, 15), p.selected)
    }

    @Test
    fun `previous month from january crosses the year boundary`() {
        val p = CalendarPosition(YearMonth(2027, 1), LocalDate(2027, 1, 15)).previousMonth()
        assertEquals(YearMonth(2026, 12), p.month)
        assertEquals(LocalDate(2026, 12, 15), p.selected)
    }

    @Test
    fun `selected day is clamped to the last day of a shorter month`() {
        val jan31 = CalendarPosition(YearMonth(2027, 1), LocalDate(2027, 1, 31))
        assertEquals(LocalDate(2027, 2, 28), jan31.nextMonth().selected)
        val leap = CalendarPosition(YearMonth(2028, 1), LocalDate(2028, 1, 31))
        assertEquals(LocalDate(2028, 2, 29), leap.nextMonth().selected)
        val mar31 = CalendarPosition(YearMonth(2026, 3), LocalDate(2026, 3, 31))
        assertEquals(LocalDate(2026, 2, 28), mar31.previousMonth().selected)
        assertEquals(LocalDate(2026, 4, 30), mar31.nextMonth().selected)
    }

    @Test
    fun `selected date always belongs to the visible month after any sequence of switches`() {
        var p = CalendarPosition(YearMonth(2026, 1), LocalDate(2026, 1, 31))
        repeat(30) {
            p = p.nextMonth()
            assertEquals(p.month, YearMonth(p.selected.year, p.selected.month))
        }
        repeat(60) {
            p = p.previousMonth()
            assertEquals(p.month, YearMonth(p.selected.year, p.selected.month))
        }
    }

    @Test
    fun `selecting a neighbour month cell switches the visible month`() {
        val p = CalendarPosition(YearMonth(2026, 11), LocalDate(2026, 11, 2)).select(LocalDate(2026, 12, 3))
        assertEquals(YearMonth(2026, 12), p.month)
        assertEquals(LocalDate(2026, 12, 3), p.selected)
    }

    @Test
    fun `initial position and go to today use the supplied today`() {
        val today = LocalDate(2026, 11, 9)
        val p = initialPosition(today)
        assertEquals(YearMonth(2026, 11), p.month)
        assertEquals(today, p.selected)
        val moved = p.nextMonth().nextMonth().goToToday(today)
        assertEquals(p, moved)
    }

    @Test
    fun `navigation stops at the storage year limits`() {
        val last = CalendarPosition(YearMonth(9998, 12), LocalDate(9998, 12, 1))
        assertFalse(last.canGoNext)
        assertEquals(last, last.nextMonth())
        val first = CalendarPosition(YearMonth(1, 1), LocalDate(1, 1, 1))
        assertFalse(first.canGoPrevious)
        assertEquals(first, first.previousMonth())
    }

    @Test
    fun `calendar header starts on Sunday with single letters`() {
        val header = weekdayOrder().map { CalendarStrings.weekdayShort(it) }
        // Letters come from the system locale (covered by LocaleDates*Test); here only the Sunday-first order matters.
        assertEquals(weekdayOrder().map { localWeekdayNarrow(it) }, header)
        assertEquals(DayOfWeek.SUNDAY, weekdayOrder().first())
        // The default grid also starts on Sunday; 1 November 2026 is a Sunday.
        val g = buildMonthGrid(YearMonth(2026, 11))
        assertEquals(DayOfWeek.SUNDAY, g.firstDayOfWeek)
        assertEquals(LocalDate(2026, 11, 1), g.weeks.first().first().date)
    }

    @Test
    fun `records count uses English singular and plural`() {
        assertEquals("no entries", CalendarStrings.recordsCount(0))
        assertEquals("1 entry", CalendarStrings.recordsCount(1))
        assertEquals("2 entries", CalendarStrings.recordsCount(2))
        assertEquals("21 entries", CalendarStrings.recordsCount(21))
    }
}
