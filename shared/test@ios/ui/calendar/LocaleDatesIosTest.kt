package app.servicecalendar.ui.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import platform.Foundation.NSLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Acceptance tests for the iOS locale-aware calendar names. Do not edit to make them pass. */
class LocaleDatesIosTest {
    private val ptBR = NSLocale(localeIdentifier = "pt_BR")
    private val us = NSLocale(localeIdentifier = "en_US")
    private val uk = NSLocale(localeIdentifier = "uk_UA")

    private val sundayFirst = listOf(
        DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
    )

    @Test
    fun monthNameIsStandaloneInTheGivenLocale() {
        assertEquals("novembro", localMonthNameInternal(Month.NOVEMBER, ptBR).lowercase())
        assertEquals("janeiro", localMonthNameInternal(Month.JANUARY, ptBR).lowercase())
        assertEquals("November", localMonthNameInternal(Month.NOVEMBER, us))
        assertEquals("листопад", localMonthNameInternal(Month.NOVEMBER, uk).lowercase())
    }

    @Test
    fun narrowWeekdaysAreUppercaseSingleLettersInSundayFirstOrder() {
        assertEquals(listOf("D", "S", "T", "Q", "Q", "S", "S"), sundayFirst.map { localWeekdayNarrowInternal(it, ptBR) })
        assertEquals(listOf("S", "M", "T", "W", "T", "F", "S"), sundayFirst.map { localWeekdayNarrowInternal(it, us) })
    }

    @Test
    fun fullWeekdayMatchesTheRequestedDay() {
        assertEquals("Monday", localWeekdayFullInternal(DayOfWeek.MONDAY, us))
        assertEquals("Sunday", localWeekdayFullInternal(DayOfWeek.SUNDAY, us))
        assertEquals("Saturday", localWeekdayFullInternal(DayOfWeek.SATURDAY, us))
        assertEquals("segunda-feira", localWeekdayFullInternal(DayOfWeek.MONDAY, ptBR).lowercase())
    }

    @Test
    fun mediumDateShowsTheRightDayMonthAndYear() {
        val us18 = localMediumDateInternal(LocalDate(2026, 11, 18), us)
        assertTrue(us18.contains("Nov") && us18.contains("18") && us18.contains("2026"), us18)
        val first = localMediumDateInternal(LocalDate(2026, 3, 1), us)
        assertTrue(first.contains("Mar") && first.contains(" 1,"), first)
        val last = localMediumDateInternal(LocalDate(2026, 12, 31), us)
        assertTrue(last.contains("Dec") && last.contains("31") && last.contains("2026"), last)
    }
}
