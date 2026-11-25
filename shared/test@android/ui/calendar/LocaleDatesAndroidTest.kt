package app.servicecalendar.ui.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Acceptance tests for the Android (JVM) locale-aware calendar names. Do not edit to make them pass. */
class LocaleDatesAndroidTest {
    private val ptBR = Locale("pt", "BR")
    private val us = Locale.US
    private val uk = Locale("uk", "UA")

    private val sundayFirst = listOf(
        DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
    )

    @Test
    fun monthNameIsStandaloneInTheGivenLocale() {
        assertEquals("novembro", localMonthNameInternal(Month.NOVEMBER, ptBR).lowercase(ptBR))
        assertEquals("janeiro", localMonthNameInternal(Month.JANUARY, ptBR).lowercase(ptBR))
        assertEquals("November", localMonthNameInternal(Month.NOVEMBER, us))
        // Nominative (stand-alone) form, not the genitive "листопада" used inside dates.
        assertEquals("листопад", localMonthNameInternal(Month.NOVEMBER, uk).lowercase(uk))
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
        assertEquals("segunda-feira", localWeekdayFullInternal(DayOfWeek.MONDAY, ptBR).lowercase(ptBR))
        assertEquals("domingo", localWeekdayFullInternal(DayOfWeek.SUNDAY, ptBR).lowercase(ptBR))
    }

    @Test
    fun mediumDateShowsTheRightDayMonthAndYear() {
        val us18 = localMediumDateInternal(LocalDate(2026, 11, 18), us)
        assertTrue(us18.contains("Nov") && us18.contains("18") && us18.contains("2026"), us18)
        val pt18 = localMediumDateInternal(LocalDate(2026, 11, 18), ptBR)
        assertTrue(pt18.contains("nov") && pt18.contains("18") && pt18.contains("2026"), pt18)
        // First and last day of a month must not shift (time zone / Calendar field bugs).
        val first = localMediumDateInternal(LocalDate(2026, 3, 1), us)
        assertTrue(first.contains("Mar") && first.contains(" 1,"), first)
        val last = localMediumDateInternal(LocalDate(2026, 12, 31), us)
        assertTrue(last.contains("Dec") && last.contains("31") && last.contains("2026"), last)
    }
}
