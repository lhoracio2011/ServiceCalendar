package app.servicecalendar.ui.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate

/** Conversion between a date and the UTC milliseconds used by the Material3 date picker. */
class PickerDatesTest {
    @Test
    fun `date maps to midnight UTC millis`() {
        assertEquals(0L, LocalDate(1970, 1, 1).toPickerMillis())
        assertEquals(1_793_491_200_000L, LocalDate(2026, 11, 1).toPickerMillis())
        assertEquals(1_793_577_600_000L, LocalDate(2026, 11, 2).toPickerMillis())
        assertEquals(-86_400_000L, LocalDate(1969, 12, 31).toPickerMillis())
    }

    @Test
    fun `millis map back to the same date at any time of the UTC day`() {
        val nov2 = 1_793_577_600_000L
        assertEquals(LocalDate(2026, 11, 2), pickerMillisToDate(nov2))
        assertEquals(LocalDate(2026, 11, 2), pickerMillisToDate(nov2 + 13 * 3_600_000L))
        assertEquals(LocalDate(2026, 11, 2), pickerMillisToDate(nov2 + 86_400_000L - 1))
        assertEquals(LocalDate(2026, 11, 3), pickerMillisToDate(nov2 + 86_400_000L))
        assertEquals(LocalDate(2026, 11, 1), pickerMillisToDate(nov2 - 1))
    }

    @Test
    fun `negative millis round down to the earlier day`() {
        assertEquals(LocalDate(1969, 12, 31), pickerMillisToDate(-1L))
        assertEquals(LocalDate(1969, 12, 31), pickerMillisToDate(-86_400_000L))
        assertEquals(LocalDate(1969, 12, 30), pickerMillisToDate(-86_400_001L))
    }

    @Test
    fun `round trip keeps the date including leap day and year ends`() {
        for (date in listOf(LocalDate(2024, 2, 29), LocalDate(2026, 12, 31), LocalDate(2027, 1, 1), LocalDate(2000, 1, 1))) {
            assertEquals(date, pickerMillisToDate(date.toPickerMillis()))
        }
    }
}
